package io.bluetape4k.aws.kotlin.sts

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldNotBeBlank
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * AWS Kotlin SDK [aws.sdk.kotlin.services.sts.StsClient] 확장 함수 테스트.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class StsClientTest: AbstractKotlinStsTest() {

    companion object: KLogging()

    @Test
    @Order(1)
    fun `StsClient 인스턴스 생성`() = runSuspendIO {
        withStsClient(
            localStackServer.endpointUrl,
            localStackServer.region,
            localStackServer.credentialsProvider,
        ) { client ->
            client.shouldNotBeNull()
        }
    }

    @Test
    @Order(2)
    fun `호출자 신원 조회`() = runSuspendIO {
        withStsClient(
            localStackServer.endpointUrl,
            localStackServer.region,
            localStackServer.credentialsProvider,
        ) { client ->
            val response = client.getCallerIdentity()

            log.debug { "userId=${response.userId}, account=${response.account}, arn=${response.arn}" }

            response.shouldNotBeNull()
            response.userId.shouldNotBeNull().shouldNotBeBlank()
            response.account.shouldNotBeNull().shouldNotBeBlank()
            response.arn.shouldNotBeNull().shouldNotBeBlank()
        }
    }

    @Test
    @Order(3)
    fun `IAM 역할 임시 맡기 (AssumeRole)`() = runSuspendIO {
        withStsClient(
            localStackServer.endpointUrl,
            localStackServer.region,
            localStackServer.credentialsProvider,
        ) { client ->
            // LocalStack 환경에서는 임의의 ARN으로도 동작합니다.
            val roleArn = "arn:aws:iam::000000000000:role/TestRole"
            val sessionName = "kotlin-test-session"

            val response = client.assumeRole(roleArn, sessionName)

            log.debug { "assumeRole credentials=${response.credentials}" }

            response.shouldNotBeNull()
            response.credentials.shouldNotBeNull()
            response.credentials.shouldNotBeNull().accessKeyId.shouldNotBeNull().shouldNotBeBlank()
            response.credentials.shouldNotBeNull().secretAccessKey.shouldNotBeNull().shouldNotBeBlank()
            response.credentials.shouldNotBeNull().sessionToken.shouldNotBeNull().shouldNotBeBlank()
        }
    }

    @Test
    @Order(4)
    fun `임시 세션 자격 증명 발급 (GetSessionToken)`() = runSuspendIO {
        withStsClient(
            localStackServer.endpointUrl,
            localStackServer.region,
            localStackServer.credentialsProvider,
        ) { client ->
            val response = client.getSessionToken(durationSeconds = 900)

            log.debug { "sessionToken credentials=${response.credentials}" }

            response.shouldNotBeNull()
            response.credentials.shouldNotBeNull()
            response.credentials.shouldNotBeNull().accessKeyId.shouldNotBeNull().shouldNotBeBlank()
            response.credentials.shouldNotBeNull().secretAccessKey.shouldNotBeNull().shouldNotBeBlank()
            response.credentials.shouldNotBeNull().sessionToken.shouldNotBeNull().shouldNotBeBlank()
        }
    }

    @Test
    @Order(5)
    fun `AssumeRole durationSeconds 범위 검증`() = runSuspendIO {
        withStsClient(
            localStackServer.endpointUrl,
            localStackServer.region,
            localStackServer.credentialsProvider,
        ) { client ->
            assertFailsWith<IllegalArgumentException> {
                client.assumeRole(
                    roleArn = "arn:aws:iam::000000000000:role/TestRole",
                    sessionName = "invalid-session",
                    durationSeconds = 899,
                )
            }
        }
    }

    @Test
    @Order(6)
    fun `GetSessionToken durationSeconds 범위 검증`() = runSuspendIO {
        withStsClient(
            localStackServer.endpointUrl,
            localStackServer.region,
            localStackServer.credentialsProvider,
        ) { client ->
            assertFailsWith<IllegalArgumentException> {
                client.getSessionToken(durationSeconds = 899)
            }
        }
    }
}
