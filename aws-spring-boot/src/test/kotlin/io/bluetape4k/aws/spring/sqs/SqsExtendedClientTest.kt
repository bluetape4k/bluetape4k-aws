@file:Suppress("MaxLineLength")

package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.codec.Base58
import io.bluetape4k.aws.spring.s3.S3BoundedObjectReadOperations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.SendMessageResponse
import java.util.Base64

class SqsExtendedClientTest {

    @Test
    fun `payload at threshold stays inline and never calls S3`() = runTest {
        val queueUrl = queueUrl()
        val policy = policy()
        val sqs = mockk<SqsFullRequestOperations>()
        val s3 = mockk<io.bluetape4k.aws.spring.s3.S3Operations>()
        val requestSlot = slot<SqsSendRequest>()
        coEvery { sqs.send(capture(requestSlot)) } returns sendResponse("inline")
        val client = client(sqs, s3, policy, queueUrl)
        val body = "x".repeat(policy.offloadThresholdBytes)

        val result = client.send(
            SqsExtendedSendRequest(
                request = SqsSendRequest(queueUrl, body, messageGroupId = "group", messageDeduplicationId = "dedup"),
                contentType = "text/plain",
            ),
        )

        result.offloaded shouldBeEqualTo false
        result.pointer.shouldBeNull()
        requestSlot.captured.body shouldBeEqualTo body
        requestSlot.captured.messageGroupId shouldBeEqualTo "group"
        requestSlot.captured.messageDeduplicationId shouldBeEqualTo "dedup"
        coVerify(exactly = 0) { s3.upload(any(), any(), any<ByteArray>(), any()) }
    }

    @Test
    fun `payload above threshold uploads before sending authenticated pointer`() = runTest {
        val queueUrl = queueUrl()
        val policy = policy()
        val sqs = mockk<SqsFullRequestOperations>()
        val s3 = mockk<io.bluetape4k.aws.spring.s3.S3Operations>()
        val events = mutableListOf<String>()
        val requestSlot = slot<SqsSendRequest>()
        coEvery { s3.upload(any(), any(), any<ByteArray>(), any()) } answers {
            events += "s3-upload"
            PutObjectResponse.builder().build()
        }
        coEvery { sqs.send(capture(requestSlot)) } answers {
            events += "sqs-send"
            sendResponse("offloaded")
        }
        val client = client(sqs, s3, policy, queueUrl)
        val idempotencyKey = "idempotency-${Base58.randomString(16)}"
        val body = "payload-${Base58.randomString(16)}".repeat(20_000)

        val result = client.send(
            SqsExtendedSendRequest(
                request = SqsSendRequest(queueUrl, body, delaySeconds = 7, messageGroupId = "group", messageDeduplicationId = "dedup"),
                contentType = "application/json",
                idempotencyKey = idempotencyKey,
            ),
        )

        result.offloaded shouldBeEqualTo true
        result.pointer?.contentType shouldBeEqualTo "application/json"
        result.pointer?.toString() shouldNotContain policy.bucket
        requestSlot.captured.body shouldContain "bt4k-sqs-extended/v1."
        requestSlot.captured.body shouldNotContain body
        requestSlot.captured.messageGroupId shouldBeEqualTo "group"
        requestSlot.captured.messageDeduplicationId shouldBeEqualTo "dedup"
        events shouldBeEqualTo listOf("s3-upload", "sqs-send")
        coVerify(exactly = 1) { s3.upload(policy.bucket, any(), any<ByteArray>(), "application/json") }
    }

    @Test
    fun `offload requires idempotency key before any S3 call`() = runTest {
        val queueUrl = queueUrl()
        val policy = policy()
        val sqs = mockk<SqsFullRequestOperations>()
        val s3 = mockk<io.bluetape4k.aws.spring.s3.S3Operations>()
        val client = client(sqs, s3, policy, queueUrl)

        val error = assertFailsWith<SqsExtendedConfigurationException> {
            client.send(
                SqsExtendedSendRequest(
                    request = SqsSendRequest(queueUrl, "payload-${Base58.randomString(16)}".repeat(20_000)),
                ),
            )
        }

        error.diagnosticCode shouldBeEqualTo SqsExtendedDiagnosticCode.CONFIGURATION.value
        coVerify(exactly = 0) { s3.upload(any(), any(), any<ByteArray>(), any()) }
        coVerify(exactly = 0) { sqs.send(any<SqsSendRequest>()) }
    }

    @Test
    fun `S3 upload failure remains typed and does not attempt SQS`() = runTest {
        val queueUrl = queueUrl()
        val policy = policy()
        val sqs = mockk<SqsFullRequestOperations>()
        val s3 = mockk<io.bluetape4k.aws.spring.s3.S3Operations>()
        coEvery { s3.upload(any(), any(), any<ByteArray>(), any()) } throws IllegalStateException("transport-secret")
        val client = client(sqs, s3, policy, queueUrl)

        val error = assertFailsWith<SqsExtendedSendException> {
            client.send(
                SqsExtendedSendRequest(
                    request = SqsSendRequest(queueUrl, "payload-${Base58.randomString(16)}".repeat(20_000)),
                    idempotencyKey = Base58.randomString(16),
                ),
            )
        }

        error.failureKind shouldBeEqualTo SqsExtendedFailureKind.S3_UPLOAD
        error.orphanCleanupRequired shouldBeEqualTo false
        error.toString() shouldNotContain "transport-secret"
        coVerify(exactly = 0) { sqs.send(any<SqsSendRequest>()) }
    }

    @Test
    fun `offloaded SQS failure keeps pointer orphan invariant`() = runTest {
        val queueUrl = queueUrl()
        val policy = policy()
        val sqs = mockk<SqsFullRequestOperations>()
        val s3 = mockk<io.bluetape4k.aws.spring.s3.S3Operations>()
        coEvery { s3.upload(any(), any(), any<ByteArray>(), any()) } returns PutObjectResponse.builder().build()
        coEvery { sqs.send(any<SqsSendRequest>()) } throws IllegalStateException("sqs-secret")
        val client = client(sqs, s3, policy, queueUrl)

        val error = assertFailsWith<SqsExtendedSendException> {
            client.send(
                SqsExtendedSendRequest(
                    request = SqsSendRequest(queueUrl, "payload-${Base58.randomString(16)}".repeat(20_000)),
                    idempotencyKey = Base58.randomString(16),
                ),
            )
        }

        error.failureKind shouldBeEqualTo SqsExtendedFailureKind.SQS_SEND
        error.pointerPresent shouldBeEqualTo true
        error.orphanCleanupRequired shouldBeEqualTo true
        error.toString() shouldNotContain "sqs-secret"
        coVerify(exactly = 1) { s3.upload(any(), any(), any<ByteArray>(), any()) }
    }

    @Test
    fun `malformed UTF-16 surrogate is rejected before S3 upload`() = runTest {
        val queueUrl = queueUrl()
        val policy = policy()
        val sqs = mockk<SqsFullRequestOperations>()
        val s3 = mockk<io.bluetape4k.aws.spring.s3.S3Operations>()
        val client = client(sqs, s3, policy, queueUrl)

        assertFailsWith<SqsExtendedConfigurationException> {
            client.send(
                SqsExtendedSendRequest(
                    request = SqsSendRequest(queueUrl, "bad-\uD800"),
                    idempotencyKey = Base58.randomString(16),
                ),
            )
        }
        coVerify(exactly = 0) { s3.upload(any(), any(), any<ByteArray>(), any()) }
    }

    @Test
    fun `receive restores pointer body only through bounded capability`() = runTest {
        val queueUrl = queueUrl()
        val policy = policy()
        val signingKey = signingKey()
        val sqs = mockk<SqsFullRequestOperations>()
        val s3 = mockk<io.bluetape4k.aws.spring.s3.S3Operations>()
        val bounded = mockk<S3BoundedObjectReadOperations>()
        val client = client(sqs, s3, policy, queueUrl, bounded)
        val fingerprint = client.policyFingerprint(queueUrl)
        val pointerBody = SqsExtendedPointerCodec.encode(
            bucket = policy.bucket,
            key = "${policy.normalizedKeyPrefix()}payload/${Base58.randomString(16)}",
            contentType = "application/json",
            encrypted = false,
            queueUrl = queueUrl,
            policyFingerprint = fingerprint,
            signingKey = signingKey,
        )
        coEvery {
            sqs.receive(queueUrl, 1, 20, policy.minimumVisibilityTimeoutSeconds)
        } returns listOf(received(queueUrl, pointerBody))
        coEvery { bounded.downloadBytesBounded(policy.bucket, any(), policy.maxOffloadPayloadBytes) } returns
            "restored-${Base58.randomString(16)}".encodeToByteArray()

        val messages = client.receive(queueUrl, visibilityTimeoutSeconds = policy.minimumVisibilityTimeoutSeconds)

        messages shouldHaveSize 1
        messages.single().pointer?.bucket shouldBeEqualTo policy.bucket
        messages.single().contentType shouldBeEqualTo "application/json"
        messages.single().body shouldContain "restored-"
        coVerify(exactly = 1) { bounded.downloadBytesBounded(policy.bucket, any(), policy.maxOffloadPayloadBytes) }
        coVerify(exactly = 0) { s3.downloadBytes(any(), any()) }
    }

    @Test
    fun `receive fails closed before S3 GET when bounded capability is absent`() = runTest {
        val queueUrl = queueUrl()
        val policy = policy()
        val sqs = mockk<SqsFullRequestOperations>()
        val s3 = mockk<io.bluetape4k.aws.spring.s3.S3Operations>()
        val client = client(sqs, s3, policy, queueUrl, bounded = null)
        val pointer = SqsExtendedPointerCodec.encode(
            bucket = policy.bucket,
            key = "${policy.normalizedKeyPrefix()}payload/${Base58.randomString(16)}",
            contentType = null,
            encrypted = false,
            queueUrl = queueUrl,
            policyFingerprint = client.policyFingerprint(queueUrl),
            signingKey = signingKey(),
        )
        coEvery { sqs.receive(queueUrl, 1, 20, policy.minimumVisibilityTimeoutSeconds) } returns
            listOf(received(queueUrl, pointer))

        assertFailsWith<SqsExtendedPayloadReadException> {
            client.receive(queueUrl, visibilityTimeoutSeconds = policy.minimumVisibilityTimeoutSeconds)
        }
        coVerify(exactly = 0) { s3.downloadBytes(any(), any()) }
    }

    @Test
    fun `policy-less queue treats pointer-looking body as opaque inline`() = runTest {
        val queueUrl = queueUrl()
        val sqs = mockk<SqsFullRequestOperations>()
        val s3 = mockk<io.bluetape4k.aws.spring.s3.S3Operations>()
        val properties = SqsExtendedClientProperties(enabled = true, consumerEnabled = true)
        val client = SqsExtendedClient(
            sqsOperations = sqs,
            s3Operations = s3,
            boundedS3Operations = null,
            s3MetadataOperations = null,
            encryptedS3Operations = null,
            encryptionIdentity = null,
            properties = properties,
        )
        val body = "bt4k-sqs-extended/v1.not-a-pointer"
        coEvery { sqs.receive(queueUrl, 1, 20, 30) } returns listOf(received(queueUrl, body))

        val result = client.receive(queueUrl)

        result.single().body shouldBeEqualTo body
        result.single().pointer.shouldBeNull()
        coVerify(exactly = 0) { s3.downloadBytes(any(), any()) }
    }

    @Test
    fun `receive flow is cold until collection`() = runTest {
        val queueUrl = queueUrl()
        val policy = policy()
        val sqs = mockk<SqsFullRequestOperations>()
        val s3 = mockk<io.bluetape4k.aws.spring.s3.S3Operations>()
        val client = client(sqs, s3, policy, queueUrl)
        coEvery { sqs.receive(queueUrl, 1, 20, policy.minimumVisibilityTimeoutSeconds) } returns
            listOf(received(queueUrl, "inline"))

        val flow = client.receiveFlow(queueUrl, visibilityTimeoutSeconds = policy.minimumVisibilityTimeoutSeconds)
        coVerify(exactly = 0) { sqs.receive(any(), any(), any(), any()) }
        flow.first().body shouldBeEqualTo "inline"
        coVerify(exactly = 1) { sqs.receive(queueUrl, 1, 20, policy.minimumVisibilityTimeoutSeconds) }
    }

    @Test
    fun `receive validates single message and visibility before delegating`() = runTest {
        val queueUrl = queueUrl()
        val policy = policy()
        val sqs = mockk<SqsFullRequestOperations>()
        val s3 = mockk<io.bluetape4k.aws.spring.s3.S3Operations>()
        val client = client(sqs, s3, policy, queueUrl)

        assertFailsWith<SqsExtendedConfigurationException> {
            client.receive(queueUrl, maxMessages = 2, visibilityTimeoutSeconds = policy.minimumVisibilityTimeoutSeconds)
        }
        assertFailsWith<SqsExtendedConfigurationException> {
            client.receive(queueUrl, visibilityTimeoutSeconds = policy.minimumVisibilityTimeoutSeconds - 1)
        }
        coVerify(exactly = 0) { sqs.receive(any(), any(), any(), any()) }
    }

    private fun client(
        sqs: SqsFullRequestOperations,
        s3: io.bluetape4k.aws.spring.s3.S3Operations,
        policy: SqsExtendedClientProperties.Policy,
        queueUrl: String,
        bounded: S3BoundedObjectReadOperations? = null,
    ): SqsExtendedClient {
        val properties = SqsExtendedClientProperties(
            enabled = true,
            producerEnabled = true,
            consumerEnabled = true,
            queues = mapOf("queue" to SqsExtendedClientProperties.QueuePolicy(queueUrl, policy)),
            security = SqsExtendedClientProperties.Security(
                mapOf(policy.pointerSigningKeyRef to Base64.getUrlEncoder().withoutPadding().encodeToString(signingKey())),
            ),
        )
        return SqsExtendedClient(
            sqsOperations = sqs,
            s3Operations = s3,
            boundedS3Operations = bounded,
            s3MetadataOperations = null,
            encryptedS3Operations = null,
            encryptionIdentity = null,
            properties = properties,
        )
    }

    private fun policy(bucket: String = "bucket-${Base58.randomString(16)}") =
        SqsExtendedClientProperties.Policy(
            bucket = bucket,
            offloadThresholdBytes = 32,
            maxInlineBytes = 128,
            maxOffloadPayloadBytes = 1_048_576,
            configuredSqsRetentionSeconds = 14 * 3_600,
            configuredMaxVisibilityRetryWindowSeconds = 7 * 3_600,
            rollbackDeadlineSeconds = 24 * 3_600,
        )

    private fun queueUrl(): String =
        "https://sqs.us-east-1.amazonaws.com/123456789012/${Base58.randomString(16)}"

    private fun signingKey(): ByteArray = ByteArray(32) { index -> index.toByte() }

    private fun sendResponse(messageId: String): SendMessageResponse =
        SendMessageResponse.builder().messageId(messageId).build()

    private fun received(queueUrl: String, body: String): SqsReceivedMessage =
        SqsReceivedMessage(
            queueUrl = queueUrl,
            message = Message.builder()
                .messageId("message-${Base58.randomString(16)}")
                .receiptHandle("receipt-${Base58.randomString(16)}")
                .body(body)
                .build(),
        )
}
