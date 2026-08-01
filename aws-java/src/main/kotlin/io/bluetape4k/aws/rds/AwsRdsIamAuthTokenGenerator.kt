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
 * Amazon RDS IAM 인증 토큰 서명에 사용하는 요청입니다.
 *
 * [hostname]에는 AWS가 IAM 토큰 생성에 사용하는 실제 RDS 엔드포인트 호스트 이름을 지정해야 합니다.
 * 서명에는 사용자 정의 DNS 별칭을 사용하지 마세요.
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
 * 값이 노출되지 않는 Amazon RDS IAM 인증 토큰을 생성합니다.
 *
 * 구현은 블로킹 방식입니다. 토큰 서명은 로컬에서 수행되지만 구성된 AWS 자격 증명 체인에 따라
 * 자격 증명 공급자 탐색이 블로킹될 수 있으므로 호출자가 실행 컨텍스트를 선택해야 합니다.
 */
fun interface AwsRdsIamAuthTokenGenerator {

    /**
     * [request]에 대한 토큰을 생성합니다.
     */
    fun generate(request: AwsRdsIamAuthTokenRequest): AwsRdsIamAuthToken
}

/**
 * AWS SDK Java v2 기반 RDS IAM 인증 토큰 생성기입니다.
 *
 * 전달받은 [rdsUtilities]의 수명 주기는 호출자가 관리합니다. 이 생성기는 이를 닫거나 소유하지 않습니다.
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
 * Amazon RDS IAM 토큰 생성 실패를 나타내며 민감한 값을 노출하지 않는 예외입니다.
 */
class AwsRdsIamAuthTokenException: AwsBluetapeException {
    constructor(): super()
    constructor(message: String): super(message)
    constructor(message: String, cause: Throwable): super(message, cause)
    constructor(cause: Throwable): super(cause)
}
