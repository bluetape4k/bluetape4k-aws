package io.bluetape4k.aws.exposed

import io.bluetape4k.aws.rds.AwsRdsIamAuthTokenException as CoreRdsIamAuthTokenException
import io.bluetape4k.aws.rds.AwsRdsIamAuthTokenGenerator as CoreRdsIamAuthTokenGenerator
import io.bluetape4k.aws.rds.AwsRdsIamAuthTokenRequest as CoreRdsIamAuthTokenRequest
import io.bluetape4k.aws.rds.AwsSdkRdsIamAuthTokenGenerator as CoreAwsSdkRdsIamAuthTokenGenerator
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.rds.RdsUtilities
import java.io.Serializable
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * physical JDBC connection을 열 때 사용할 인증 방식입니다.
 */
enum class AwsDatabaseAuthenticationMode {
    /**
     * 설정된 static [AwsDatabaseConnectionProperties.password]를 사용합니다.
     */
    STATIC_PASSWORD,

    /**
     * connection 생성 시 Amazon RDS IAM authentication token을 생성합니다.
     */
    RDS_IAM,
}

/**
 * JDBC 연결 대상 하나에 대한 Amazon RDS IAM authentication 설정입니다.
 *
 * [hostname]은 token signing에 사용할 실제 RDS endpoint hostname이어야 합니다.
 * Amazon RDS IAM token 생성은 사용자 정의 DNS alias를 지원하지 않습니다.
 */
data class AwsRdsIamAuthenticationProperties(
    /** token signing에 사용할 AWS region 이름입니다. */
    val region: String,
    /** token signing 대상 RDS endpoint hostname입니다. 사용자 정의 DNS alias가 아니어야 합니다. */
    val hostname: String,
    /** RDS endpoint port입니다. `1..65_535` 범위여야 합니다. */
    val port: Int,
    /** token에 포함할 database username입니다. `null`이면 connection username을 사용합니다. */
    val username: String? = null,
    /** 생성된 token의 유효 시간입니다. Amazon RDS IAM의 최대 TTL을 넘을 수 없습니다. */
    val tokenTtl: Duration = MAX_TOKEN_TTL,
    /** token 만료 전에 새 token을 만들 refresh 여유 시간입니다. [tokenTtl]보다 짧아야 합니다. */
    val refreshBeforeExpiry: Duration = DEFAULT_REFRESH_BEFORE_EXPIRY,
): Serializable {

    init {
        region.requireNotBlank("region")
        hostname.requireNotBlank("hostname")
        username?.requireNotBlank("username")
        port.requireInRange(MIN_PORT, MAX_PORT, "port")
        tokenTtl.requireGt(Duration.ZERO, "tokenTtl")
        tokenTtl.requireLe(MAX_TOKEN_TTL, "tokenTtl")
        refreshBeforeExpiry.requireGt(Duration.ZERO, "refreshBeforeExpiry")
        require(refreshBeforeExpiry < tokenTtl) {
            "refreshBeforeExpiry must be less than tokenTtl: $refreshBeforeExpiry >= $tokenTtl"
        }
    }

    internal fun effectiveUsername(connectionUsername: String?): String {
        val effective = username ?: connectionUsername
        return requireNotNull(effective) { "username must be configured for RDS_IAM authentication." }
            .also { it.requireNotBlank("username") }
    }

    companion object: KLogging() {
        private const val serialVersionUID: Long = -4435741150825464135L

        /**
         * Amazon RDS IAM database authentication token의 최대 수명입니다.
         */
        val MAX_TOKEN_TTL: Duration = Duration.ofMinutes(15)

        /**
         * AWS가 강제하는 TTL에 도달하기 전 token을 갱신할 기본 여유 시간입니다.
         */
        val DEFAULT_REFRESH_BEFORE_EXPIRY: Duration = Duration.ofMinutes(2)

        private const val MIN_PORT: Int = 1
        private const val MAX_PORT: Int = 65_535
    }
}

/**
 * RDS IAM authentication token generator에 전달되는 요청입니다.
 */
data class AwsRdsIamAuthTokenRequest(
    /** token signing에 사용할 AWS region 이름입니다. */
    val region: String,
    /** token signing 대상 RDS endpoint hostname입니다. */
    val hostname: String,
    /** RDS endpoint port입니다. */
    val port: Int,
    /** token에 포함할 database username입니다. */
    val username: String,
): Serializable {

    init {
        region.requireNotBlank("region")
        hostname.requireNotBlank("hostname")
        username.requireNotBlank("username")
        port.requireInRange(MIN_PORT, MAX_PORT, "port")
    }

    companion object: KLogging() {
        private const val serialVersionUID: Long = -1023639141190871715L

        private const val MIN_PORT: Int = 1
        private const val MAX_PORT: Int = 65_535
    }
}

/**
 * JDBC password slot에 사용할 redaction-safe RDS IAM authentication token을 생성합니다.
 *
 * 구현체는 JDBC driver에 전달하는 호출 경계를 넘어 reveal된 token 문자열을 log로 남기거나 보관하면 안 됩니다.
 */
fun interface AwsRdsIamAuthTokenGenerator {

    /**
     * [request]에 대한 token을 생성합니다.
     */
    fun generate(request: AwsRdsIamAuthTokenRequest): AwsSecretString
}

/**
 * AWS SDK Java v2 기반 RDS IAM authentication token generator입니다.
 *
 * 주입된 [rdsUtilities]는 호출자가 관리합니다. `RdsUtilities` 자체가 가벼운 utility 객체이고 호출자의
 * AWS SDK lifecycle과 공유될 수 있으므로 이 generator는 닫지 않습니다.
 */
class AwsSdkRdsIamAuthTokenGenerator private constructor(
    private val delegate: CoreRdsIamAuthTokenGenerator,
): AwsRdsIamAuthTokenGenerator {

    constructor(): this(CoreAwsSdkRdsIamAuthTokenGenerator())

    constructor(rdsUtilities: RdsUtilities): this(CoreAwsSdkRdsIamAuthTokenGenerator(rdsUtilities))

    override fun generate(request: AwsRdsIamAuthTokenRequest): AwsSecretString =
        try {
            AwsSecretString.of(
                delegate.generate(
                    CoreRdsIamAuthTokenRequest(
                        region = request.region,
                        hostname = request.hostname,
                        port = request.port,
                        username = request.username,
                    ),
                ).reveal(),
            )
        } catch (e: CoreRdsIamAuthTokenException) {
            throw AwsRdsIamAuthTokenException(
                e.message ?: "Failed to generate RDS IAM authentication token for ${request.hostname}:${request.port}.",
                e,
            )
        } catch (e: RuntimeException) {
            throw AwsRdsIamAuthTokenException(
                "Failed to generate RDS IAM authentication token for ${request.hostname}:${request.port}.",
                e,
            )
        }
}

/**
 * RDS IAM token 생성 실패를 나타내는 redaction-safe 예외입니다.
 */
class AwsRdsIamAuthTokenException(
    message: String,
    cause: Throwable? = null,
): RuntimeException(message, cause)

/**
 * physical JDBC connection을 열 때 사용할 password 값을 제공합니다.
 */
fun interface AwsDatabasePasswordProvider {

    /**
     * 현재 password를 반환합니다. password가 설정되지 않았으면 `null`을 반환합니다.
     */
    fun currentPassword(): AwsSecretString?
}

/**
 * static JDBC password와 RDS IAM token password를 위한 factory helper입니다.
 */
object AwsDatabasePasswordProviders: KLogging() {

    /**
     * [password]를 그대로 반환하는 provider를 생성합니다.
     */
    fun static(password: AwsSecretString?): AwsDatabasePasswordProvider =
        AwsDatabasePasswordProvider { password }

    /**
     * [tokenGenerator]로 [properties]에 맞는 RDS IAM token provider를 생성합니다.
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
     * [AwsDatabaseConnectionProperties.authenticationMode]에 따라 선택된 provider를 반환합니다.
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

    companion object: KLogging()

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
        /** cache에 저장된 redaction-safe RDS IAM token입니다. */
        val token: AwsSecretString,
        /** 이 시각 이후에는 token을 새로 생성해야 합니다. */
        val refreshAt: Instant,
    ): Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }
}
