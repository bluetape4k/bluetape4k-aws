package io.bluetape4k.aws.rds

import io.bluetape4k.aws.exceptions.AwsBluetapeException
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.rds.RdsUtilities
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest
import java.io.Serializable

/**
 * Request used to sign an Amazon RDS IAM authentication token.
 *
 * The [hostname] must be the actual RDS endpoint hostname used by AWS for IAM
 * token generation. Custom DNS aliases should not be used for signing.
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

    companion object: KLogging() {
        private const val serialVersionUID: Long = 3134176872350547451L

        private const val MIN_PORT: Int = 1
        private const val MAX_PORT: Int = 65_535
    }
}

/**
 * Generates a redacted Amazon RDS IAM authentication token.
 *
 * Implementations are blocking. Token signing is local, but credential
 * provider resolution may block depending on the configured AWS credential
 * chain, so callers choose the execution context.
 */
fun interface AwsRdsIamAuthTokenGenerator {

    /**
     * Generates a token for [request].
     */
    fun generate(request: AwsRdsIamAuthTokenRequest): AwsRdsIamAuthToken
}

/**
 * AWS SDK Java v2-backed RDS IAM authentication token generator.
 *
 * The supplied [rdsUtilities] is caller-managed. This generator does not close
 * or otherwise own it.
 */
class AwsSdkRdsIamAuthTokenGenerator(
    private val rdsUtilities: RdsUtilities = defaultRdsUtilities(),
): AwsRdsIamAuthTokenGenerator {

    override fun generate(request: AwsRdsIamAuthTokenRequest): AwsRdsIamAuthToken =
        try {
            val region = Region.of(request.region)
            awsRdsIamAuthTokenOf(
                rdsUtilities.generateAuthenticationToken(
                    GenerateAuthenticationTokenRequest.builder()
                        .hostname(request.hostname)
                        .port(request.port)
                        .username(request.username)
                        .region(region)
                        .build(),
                ),
            )
        } catch (e: RuntimeException) {
            throw AwsRdsIamAuthTokenException(
                "Failed to generate RDS IAM authentication token for ${request.hostname}:${request.port}.",
                e,
            )
        }

    companion object: KLogging() {
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
 * Redaction-safe exception for Amazon RDS IAM token generation failures.
 */
class AwsRdsIamAuthTokenException: AwsBluetapeException {
    constructor(): super()
    constructor(message: String): super(message)
    constructor(message: String, cause: Throwable): super(message, cause)
    constructor(cause: Throwable): super(cause)
}
