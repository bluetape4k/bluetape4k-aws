package io.bluetape4k.aws.exposed

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.rds.RdsUtilities
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest
import java.io.Serializable
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Authentication mode used when opening physical JDBC connections.
 */
enum class AwsDatabaseAuthenticationMode {
    /**
     * Uses the configured static [AwsDatabaseConnectionProperties.password].
     */
    STATIC_PASSWORD,

    /**
     * Generates Amazon RDS IAM authentication tokens at connection creation.
     */
    RDS_IAM,
}

/**
 * Amazon RDS IAM authentication settings for one JDBC connection target.
 *
 * [hostname] must be the actual RDS endpoint hostname used for token signing.
 * Custom DNS aliases are not supported by Amazon RDS IAM token generation.
 */
data class AwsRdsIamAuthenticationProperties(
    val region: String,
    val hostname: String,
    val port: Int,
    val username: String? = null,
    val tokenTtl: Duration = MAX_TOKEN_TTL,
    val refreshBeforeExpiry: Duration = DEFAULT_REFRESH_BEFORE_EXPIRY,
): Serializable {

    init {
        region.requireNotBlank("region")
        hostname.requireNotBlank("hostname")
        username?.requireNotBlank("username")
        require(port in MIN_PORT..MAX_PORT) { "port must be in $MIN_PORT..$MAX_PORT: $port" }
        require(!tokenTtl.isZero && !tokenTtl.isNegative) { "tokenTtl must be positive: $tokenTtl" }
        require(tokenTtl <= MAX_TOKEN_TTL) { "tokenTtl must not exceed $MAX_TOKEN_TTL: $tokenTtl" }
        require(!refreshBeforeExpiry.isZero && !refreshBeforeExpiry.isNegative) {
            "refreshBeforeExpiry must be positive: $refreshBeforeExpiry"
        }
        require(refreshBeforeExpiry < tokenTtl) {
            "refreshBeforeExpiry must be less than tokenTtl: $refreshBeforeExpiry >= $tokenTtl"
        }
    }

    internal fun effectiveUsername(connectionUsername: String?): String {
        val effective = username ?: connectionUsername
        return requireNotNull(effective) { "username must be configured for RDS_IAM authentication." }
            .also { it.requireNotBlank("username") }
    }

    companion object {
        private const val serialVersionUID: Long = -4435741150825464135L

        /**
         * Amazon RDS IAM database authentication token lifetime.
         */
        val MAX_TOKEN_TTL: Duration = Duration.ofMinutes(15)

        /**
         * Default refresh skew before the token reaches its AWS-enforced TTL.
         */
        val DEFAULT_REFRESH_BEFORE_EXPIRY: Duration = Duration.ofMinutes(2)

        private const val MIN_PORT: Int = 1
        private const val MAX_PORT: Int = 65_535
    }
}

/**
 * Request passed to an RDS IAM authentication token generator.
 */
data class AwsRdsIamAuthTokenRequest(
    val region: String,
    val hostname: String,
    val port: Int,
    val username: String,
): Serializable {

    init {
        region.requireNotBlank("region")
        hostname.requireNotBlank("hostname")
        username.requireNotBlank("username")
        require(port in MIN_PORT..MAX_PORT) { "port must be in $MIN_PORT..$MAX_PORT: $port" }
    }

    companion object {
        private const val serialVersionUID: Long = -1023639141190871715L

        private const val MIN_PORT: Int = 1
        private const val MAX_PORT: Int = 65_535
    }
}

/**
 * Generates a redacted RDS IAM authentication token for a JDBC password slot.
 *
 * Implementations must not log or retain the revealed token string beyond the
 * call boundary needed by the JDBC driver.
 */
fun interface AwsRdsIamAuthTokenGenerator {

    /**
     * Generates a token for [request].
     */
    fun generate(request: AwsRdsIamAuthTokenRequest): AwsSecretString
}

/**
 * AWS SDK Java v2-backed RDS IAM authentication token generator.
 *
 * The supplied [rdsUtilities] is caller-managed. This generator does not close
 * it because `RdsUtilities` itself is a lightweight utility object and may be
 * shared with the caller's AWS SDK lifecycle.
 */
class AwsSdkRdsIamAuthTokenGenerator(
    private val rdsUtilities: RdsUtilities = defaultRdsUtilities(),
): AwsRdsIamAuthTokenGenerator {

    override fun generate(request: AwsRdsIamAuthTokenRequest): AwsSecretString =
        try {
            val region = Region.of(request.region)
            AwsSecretString.of(
                rdsUtilities.generateAuthenticationToken(
                    GenerateAuthenticationTokenRequest.builder()
                        .hostname(request.hostname)
                        .port(request.port)
                        .username(request.username)
                        .region(region)
                        .build()
                )
            )
        } catch (e: RuntimeException) {
            throw AwsRdsIamAuthTokenException(
                "Failed to generate RDS IAM authentication token for ${request.hostname}:${request.port}.",
                e,
            )
        }

    companion object {
        private const val RDS_UTILITIES_CLASS_NAME: String = "software.amazon.awssdk.services.rds.RdsUtilities"

        private fun defaultRdsUtilities(): RdsUtilities {
            requireRdsUtilitiesClass()
            return RdsUtilities.builder()
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .build()
        }

        private fun requireRdsUtilitiesClass() {
            try {
                Class.forName(RDS_UTILITIES_CLASS_NAME)
            } catch (e: ClassNotFoundException) {
                throw AwsRdsIamAuthTokenException(
                    "AWS SDK RDS module is required for RDS IAM authentication. " +
                            "Add runtime dependency 'software.amazon.awssdk:rds'.",
                    e,
                )
            }
        }
    }
}

/**
 * Redaction-safe exception for RDS IAM token generation failures.
 */
class AwsRdsIamAuthTokenException(
    message: String,
    cause: Throwable? = null,
): RuntimeException(message, cause)

/**
 * Supplies the password value used when a physical JDBC connection is opened.
 */
fun interface AwsDatabasePasswordProvider {

    /**
     * Returns the current password, or `null` when no password is configured.
     */
    fun currentPassword(): AwsSecretString?
}

/**
 * Factory helpers for static JDBC passwords and RDS IAM token passwords.
 */
object AwsDatabasePasswordProviders {

    /**
     * Returns a provider for [password].
     */
    fun static(password: AwsSecretString?): AwsDatabasePasswordProvider =
        AwsDatabasePasswordProvider { password }

    /**
     * Returns a provider for [properties] using [tokenGenerator].
     */
    fun rdsIam(
        properties: AwsDatabaseConnectionProperties,
        tokenGenerator: AwsRdsIamAuthTokenGenerator,
        clock: Clock = Clock.systemUTC(),
    ): AwsDatabasePasswordProvider {
        require(properties.authenticationMode == AwsDatabaseAuthenticationMode.RDS_IAM) {
            "authenticationMode must be RDS_IAM."
        }

        val rdsIam = requireNotNull(properties.rdsIam) {
            "rdsIam must be configured when authenticationMode is RDS_IAM."
        }
        val request = AwsRdsIamAuthTokenRequest(
            region = rdsIam.region,
            hostname = rdsIam.hostname,
            port = rdsIam.port,
            username = rdsIam.effectiveUsername(properties.username),
        )
        Region.of(request.region)

        return RefreshingRdsIamPasswordProvider(
            request = request,
            tokenTtl = rdsIam.tokenTtl,
            refreshBeforeExpiry = rdsIam.refreshBeforeExpiry,
            tokenGenerator = tokenGenerator,
            clock = clock,
        )
    }

    /**
     * Returns the provider selected by [AwsDatabaseConnectionProperties.authenticationMode].
     */
    fun from(
        properties: AwsDatabaseConnectionProperties,
        rdsIamTokenGenerator: AwsRdsIamAuthTokenGenerator,
        clock: Clock = Clock.systemUTC(),
    ): AwsDatabasePasswordProvider =
        when (properties.authenticationMode) {
            AwsDatabaseAuthenticationMode.STATIC_PASSWORD -> static(properties.password)
            AwsDatabaseAuthenticationMode.RDS_IAM -> rdsIam(properties, rdsIamTokenGenerator, clock)
        }
}

private class RefreshingRdsIamPasswordProvider(
    private val request: AwsRdsIamAuthTokenRequest,
    private val tokenTtl: Duration,
    private val refreshBeforeExpiry: Duration,
    private val tokenGenerator: AwsRdsIamAuthTokenGenerator,
    private val clock: Clock,
): AwsDatabasePasswordProvider {

    private val refreshLock = ReentrantLock()

    @Volatile
    private var cached: CachedToken? = null

    override fun currentPassword(): AwsSecretString {
        val now = clock.instant()
        cached?.takeIf { now.isBefore(it.refreshAt) }?.let { return it.token }

        return refreshLock.withLock {
            val lockedNow = clock.instant()
            cached?.takeIf { lockedNow.isBefore(it.refreshAt) }?.let { return@withLock it.token }

            val generated = try {
                tokenGenerator.generate(request)
            } catch (e: AwsRdsIamAuthTokenException) {
                throw e
            } catch (e: RuntimeException) {
                throw AwsRdsIamAuthTokenException(
                    "Failed to generate RDS IAM authentication token for ${request.hostname}:${request.port}.",
                    e,
                )
            }
            val refreshed = CachedToken(
                token = generated,
                refreshAt = lockedNow.plus(tokenTtl).minus(refreshBeforeExpiry),
            )
            cached = refreshed
            refreshed.token
        }
    }

    override fun toString(): String =
        "RefreshingRdsIamPasswordProvider(request=${request.copy(username = AwsSecretString.REDACTED)})"

    private data class CachedToken(
        val token: AwsSecretString,
        val refreshAt: Instant,
    )
}
