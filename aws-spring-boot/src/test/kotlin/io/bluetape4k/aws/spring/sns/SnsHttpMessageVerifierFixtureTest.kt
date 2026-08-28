package io.bluetape4k.aws.spring.sns

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.http.ExecutableHttpRequest
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.HttpExecuteResponse
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.SdkHttpFullResponse
import software.amazon.awssdk.messagemanager.sns.SnsMessageManager
import software.amazon.awssdk.messagemanager.sns.internal.CertificateRetriever
import software.amazon.awssdk.regions.Region
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * AWS SNS signed HTTP fixture를 외부 AWS endpoint 없이 검증합니다.
 *
 * 실제 SNS 전송은 Floci가 서명된 HTTP delivery를 생성하지 않으므로 SDK
 * `SdkHttpClient` 경계를 결정적인 로컬 double로 대체합니다.
 */
class SnsHttpMessageVerifierFixtureTest {

    @Test
    fun `verifier accepts the long lived AWS format signature version 1 fixture`() {
        val client = StubHttpClient { successResponse(signingCertificate) }

        withVerifier(client) { verifier ->
            val message = verifier.verify(
                notificationV1,
                messageTypeHeader = "Notification",
                expectedTopicArn = TOPIC_ARN,
            )

            message.topicArn shouldBeEqualTo TOPIC_ARN
            message.signatureVersion shouldBeEqualTo "1"
            client.requestCount shouldBeEqualTo 1
        }
    }

    @Test
    fun `verifier accepts the long lived AWS format signature version 2 fixture`() {
        val client = StubHttpClient { successResponse(signingCertificate) }

        withVerifier(client) { verifier ->
            val message = verifier.verify(
                notificationV2,
                messageTypeHeader = "Notification",
                expectedTopicArn = TOPIC_ARN,
            )

            message.topicArn shouldBeEqualTo TOPIC_ARN
            message.signatureVersion shouldBeEqualTo "2"
            client.requestCount shouldBeEqualTo 1
        }
    }

    @Test
    fun `signature mutation is rejected after certificate retrieval`() {
        val client = StubHttpClient { successResponse(signingCertificate) }
        val tampered = notificationV1.replaceFirst("OVdC", "AVdC")

        withVerifier(client) { verifier ->
            val failure = assertFailsWith<SdkClientException> {
                verifier.verify(tampered, messageTypeHeader = "Notification")
            }

            failure.message.orEmpty() shouldContain "signature"
            client.requestCount shouldBeEqualTo 1
        }
    }

    @Test
    fun `canonical field mutation is rejected`() {
        val client = StubHttpClient { successResponse(signingCertificate) }
        val tampered = notificationV1.replace("Issue 513 fixture", "Issue 513 changed")

        withVerifier(client) { verifier ->
            val failure = assertFailsWith<SdkClientException> {
                verifier.verify(tampered, messageTypeHeader = "Notification")
            }

            failure.message.orEmpty() shouldContain "signature"
            client.requestCount shouldBeEqualTo 1
        }
    }

    @Test
    fun `certificate host mutation is rejected before network`() {
        val client = StubHttpClient { successResponse(signingCertificate) }
        val tampered = notificationV1.replace(
            "https://sns.us-west-2.amazonaws.com/",
            "https://evil.example.com/",
        )

        withVerifier(client) { verifier ->
            val failure = assertFailsWith<IllegalArgumentException> {
                verifier.verify(tampered, messageTypeHeader = "Notification")
            }

            failure.message.orEmpty() shouldContain "Amazon SNS host"
            client.requestCount shouldBeEqualTo 0
        }
    }

    @Test
    fun `expected topic mutation is rejected before certificate retrieval`() {
        val client = StubHttpClient { successResponse(signingCertificate) }
        val tampered = notificationV1.replace(TOPIC_ARN, OTHER_TOPIC_ARN)

        withVerifier(client) { verifier ->
            val failure = assertFailsWith<IllegalArgumentException> {
                verifier.verify(
                    tampered,
                    messageTypeHeader = "Notification",
                    expectedTopicArn = TOPIC_ARN,
                )
            }

            failure.message.orEmpty() shouldContain "does not match expectedTopicArn"
            client.requestCount shouldBeEqualTo 0
        }
    }

    @Test
    fun `corrupt certificate is rejected with observable sdk failure`() {
        val client = StubHttpClient { successResponse("not a certificate".toByteArray(StandardCharsets.UTF_8)) }

        withVerifier(client) { verifier ->
            val failure = assertFailsWith<SdkClientException> {
                verifier.verify(notificationV2, messageTypeHeader = "Notification")
            }

            failure.message.orEmpty() shouldContain "X509 PEM"
            client.requestCount shouldBeEqualTo 1
        }
    }

    @Test
    fun `official expired certificate fixture is rejected by sdk retriever`() {
        val client = StubHttpClient { successResponse(expiredCertificate) }
        val retriever = CertificateRetriever(
            client,
            TEST_CERT_HOST,
            TEST_CERT_HOST,
        )

        try {
            val failure = assertFailsWith<SdkClientException> {
                retriever.retrieveCertificate(URI.create("https://$TEST_CERT_HOST/expired.pem"))
            }

            failure.message.orEmpty() shouldContain "certificate is expired"
            client.requestCount shouldBeEqualTo 1
        } finally {
            retriever.close()
        }
    }

    @Test
    fun `certificate cache returns a hit without a second fetch`() {
        val client = StubHttpClient { successResponse(signingCertificate) }

        withVerifier(client) { verifier ->
            verifier.verify(notificationV2, messageTypeHeader = "Notification")
            verifier.verify(notificationV2, messageTypeHeader = "Notification")

            client.requestCount shouldBeEqualTo 1
        }
    }

    @Test
    fun `certificate cache evicts the oldest entry at its bounded limit`() {
        val client = StubHttpClient { successResponse(signingCertificate) }

        withVerifier(client) { verifier ->
            repeat(CERTIFICATE_CACHE_SIZE + 1) { index ->
                verifier.verify(
                    notificationV2.replace(BASE_CERTIFICATE_URL, certificateUrl(index)),
                    messageTypeHeader = "Notification",
                )
            }
            client.requestCount shouldBeEqualTo CERTIFICATE_CACHE_SIZE + 1

            verifier.verify(notificationV2, messageTypeHeader = "Notification")
            client.requestCount shouldBeEqualTo CERTIFICATE_CACHE_SIZE + 2
        }
    }

    @Test
    fun `concurrent verification shares a single certificate fetch`() {
        val client = StubHttpClient { successResponse(signingCertificate) }

        withVerifier(client) { verifier ->
            MultithreadingTester()
                .workers(4)
                .rounds(1)
                .add(Runnable {
                    verifier.verify(
                        notificationV2,
                        messageTypeHeader = "Notification",
                        expectedTopicArn = TOPIC_ARN,
                    )
                })
                .run()

            client.requestCount shouldBeEqualTo 1
        }
    }

    @Test
    fun `read timeout is preserved as the sdk failure cause`() {
        val timeout = SocketTimeoutException("read timeout")
        val client = StubHttpClient { throw timeout }

        withVerifier(client) { verifier ->
            val failure = assertFailsWith<SdkClientException> {
                verifier.verify(notificationV2, messageTypeHeader = "Notification")
            }

            failure.cause shouldBeSameInstanceAs timeout
            client.requestCount shouldBeEqualTo 1
        }
    }

    @Test
    fun `connect timeout is preserved as the sdk failure cause`() {
        val timeout = ConnectException("connect timeout")
        val client = StubHttpClient { throw timeout }

        withVerifier(client) { verifier ->
            val failure = assertFailsWith<SdkClientException> {
                verifier.verify(notificationV2, messageTypeHeader = "Notification")
            }

            failure.cause shouldBeSameInstanceAs timeout
            client.requestCount shouldBeEqualTo 1
        }
    }

    @Test
    fun `response body cleanup failure is observable and body close is attempted`() {
        val cleanupFailure = IOException("response body cleanup failed")
        val bodyClosed = AtomicBoolean(false)
        val client = StubHttpClient {
            val body = object : ByteArrayInputStream(signingCertificate) {
                override fun close() {
                    bodyClosed.set(true)
                    throw cleanupFailure
                }
            }
            HttpExecuteResponse.builder()
                .response(SdkHttpFullResponse.builder().statusCode(200).build())
                .responseBody(AbortableInputStream.create(body))
                .build()
        }

        withVerifier(client) { verifier ->
            val failure = assertFailsWith<SdkClientException> {
                verifier.verify(notificationV2, messageTypeHeader = "Notification")
            }

            failure.cause shouldBeSameInstanceAs cleanupFailure
            bodyClosed.get().shouldBeTrue()
            client.requestCount shouldBeEqualTo 1
        }
    }

    @Test
    fun `interrupting an in flight certificate request releases the local worker`() {
        val requestStarted = CountDownLatch(1)
        val requestInterrupted = CountDownLatch(1)
        val client = StubHttpClient {
            requestStarted.countDown()
            try {
                CountDownLatch(1).await()
                error("certificate request was released without interruption")
            } catch (interrupted: InterruptedException) {
                requestInterrupted.countDown()
                throw IOException("certificate request cancelled", interrupted)
            }
        }
        val verifier = newVerifier(client)
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit {
            verifier.verify(notificationV2, messageTypeHeader = "Notification")
        }

        try {
            requestStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
            future.cancel(true).shouldBeTrue()
            requestInterrupted.await(5, TimeUnit.SECONDS).shouldBeTrue()
            future.isCancelled.shouldBeTrue()
            client.requestCount shouldBeEqualTo 1
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS).shouldBeTrue()
            verifier.close()
        }
    }

    private fun newVerifier(client: SdkHttpClient): SnsHttpMessageVerifier =
        SnsHttpMessageVerifier(
            SnsMessageManager.builder()
                .httpClient(client)
                .region(Region.US_WEST_2)
                .build()
        )

    private inline fun <T> withVerifier(client: SdkHttpClient, block: (SnsHttpMessageVerifier) -> T): T {
        val verifier = newVerifier(client)
        return try {
            block(verifier)
        } finally {
            verifier.close()
        }
    }

    private fun certificateUrl(index: Int): String =
        "https://sns.us-west-2.amazonaws.com/issue-513-$index.pem"

    private class StubHttpClient(
        private val responseFactory: (URI) -> HttpExecuteResponse,
    ) : SdkHttpClient {

        private val requests = CopyOnWriteArrayList<URI>()

        val requestCount: Int
            get() = requests.size

        override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest {
            val uri = request.httpRequest().getUri()
            requests.add(uri)
            return object : ExecutableHttpRequest {
                override fun call(): HttpExecuteResponse = responseFactory(uri)

                override fun abort() = Unit
            }
        }

        override fun close() = Unit
    }

    private companion object {
        const val TOPIC_ARN = "arn:aws:sns:us-west-2:123456789012:issue-513-topic"
        const val OTHER_TOPIC_ARN = "arn:aws:sns:us-west-2:123456789012:other-topic"
        const val BASE_CERTIFICATE_URL =
            "https://sns.us-west-2.amazonaws.com/SimpleNotificationService-issue-513.pem"
        const val TEST_CERT_HOST = "my-test-service.amazonaws.com"
        const val CERTIFICATE_CACHE_SIZE = 10

        val notificationV1 = fixture("notification-v1.json")
        val notificationV2 = fixture("notification-v2.json")
        val signingCertificate = fixture("signing-cert.pem").toByteArray(StandardCharsets.UTF_8)
        val expiredCertificate = fixture("expired-cert.pem").toByteArray(StandardCharsets.UTF_8)

        private fun fixture(name: String): String =
            requireNotNull(SnsHttpMessageVerifierFixtureTest::class.java.getResource("/sns-signature/$name"))
                .readText(StandardCharsets.UTF_8)

        fun successResponse(body: ByteArray): HttpExecuteResponse =
            HttpExecuteResponse.builder()
                .response(SdkHttpFullResponse.builder().statusCode(200).build())
                .responseBody(AbortableInputStream.create(ByteArrayInputStream(body)))
                .build()
    }
}
