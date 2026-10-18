package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.aws.spring.s3.S3BoundedEncryptedReadOperations
import io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionIdentity
import io.bluetape4k.aws.spring.s3.S3Operations
import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.SendMessageResponse
import java.util.*

class SqsExtendedClientEncryptionTest {

    companion object: KLoggingChannel()

    private val sqs = mockk<SqsFullRequestOperations>(relaxed = true)
    private val s3 = mockk<S3Operations>(relaxed = true)
    private val encrypted = mockk<S3BoundedEncryptedReadOperations>(relaxed = true)
    private val identity = mockk<S3ClientSideEncryptionIdentity>(relaxed = true)

    @BeforeEach
    fun beforeEach() {
        clearAllMocks()
    }


    @Test
    fun `encrypted offload delegates to bounded encryption capability`() = runTest {
        val queueUrl = queueUrl()
        val policy = policy()
        val request = slot<SqsSendRequest>()

        every { identity.keyFingerprint } returns "fingerprint"
        coEvery {
            encrypted.uploadEncrypted(any(), any(), any(), any(), any(), any())
        } returns PutObjectResponse.builder().build()
        coEvery {
            sqs.send(capture(request))
        } returns SendMessageResponse.builder().messageId("sent").build()

        val client = client(sqs, s3, encrypted, identity, policy, queueUrl)

        val result = client.send(
            SqsExtendedSendRequest(
                request = SqsSendRequest(queueUrl, "payload-${Base58.randomString(16)}".repeat(20_000)),
                idempotencyKey = Base58.randomString(16),
                contentType = "application/json",
            ),
        )

        result.offloaded.shouldBeTrue()
        result.pointer.shouldNotBeNull().encrypted.shouldBeTrue()
        request.captured.body shouldContain "bt4k-sqs-extended/v1."

        coVerify(exactly = 1) {
            encrypted.uploadEncrypted(
                policy.bucket,
                any(),
                any(),
                "application/json",
                emptyMap(),
                policy.encryption.encryptionContext,
            )
        }
        coVerify(exactly = 0) { s3.upload(any(), any(), any<ByteArray>(), any()) }
    }

    @Test
    fun `encrypted offload fails closed when current key identity differs`() = runTest {
        val queueUrl = queueUrl()
        val policy = policy()

        every { identity.keyFingerprint } returns "foreign-fingerprint"

        val client = client(sqs, s3, encrypted, identity, policy, queueUrl)

        assertFailsWith<SqsExtendedConfigurationException> {
            client.send(
                SqsExtendedSendRequest(
                    request = SqsSendRequest(queueUrl, "payload-${Base58.randomString(16)}".repeat(20_000)),
                    idempotencyKey = Base58.randomString(16),
                ),
            )
        }

        coVerify(exactly = 0) { encrypted.uploadEncrypted(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { sqs.send(any<SqsSendRequest>()) }
    }

    @Test
    fun `encrypted receive restores only through matching identity capability`() = runTest {
        val queueUrl = queueUrl()
        val policy = policy()

        every { identity.keyFingerprint } returns "fingerprint"

        val client = client(sqs, s3, encrypted, identity, policy, queueUrl)
        val pointer = SqsExtendedPointerCodec.encode(
            bucket = policy.bucket,
            key = "${policy.normalizedKeyPrefix()}payload/${Base58.randomString(16)}",
            contentType = "application/json",
            encrypted = true,
            queueUrl = queueUrl,
            policyFingerprint = client.policyFingerprint(queueUrl),
            signingKey = signingKey(),
        )
        coEvery {
            sqs.receive(queueUrl, 1, 20, policy.minimumVisibilityTimeoutSeconds)
        } returns listOf(
            SqsReceivedMessage(
                queueUrl,
                Message.builder()
                    .messageId("message-${Base58.randomString(16)}")
                    .receiptHandle("receipt-${Base58.randomString(16)}")
                    .body(pointer)
                    .build(),
            ),
        )
        coEvery {
            encrypted.downloadEncryptedBytesBounded(
                policy.bucket,
                any(),
                policy.encryption.encryptionContext,
                policy.maxOffloadPayloadBytes + 16,
            )
        } returns "encrypted-restored".encodeToByteArray()

        val result = client.receive(queueUrl, visibilityTimeoutSeconds = policy.minimumVisibilityTimeoutSeconds)
            .onEach { log.debug { "message=$it" } }

        result.single().body shouldBeEqualTo "encrypted-restored"
        result.single().pointer.shouldNotBeNull().encrypted.shouldBeTrue()
    }

    private fun client(
        sqs: SqsFullRequestOperations,
        s3: S3Operations,
        encrypted: S3BoundedEncryptedReadOperations,
        identity: S3ClientSideEncryptionIdentity,
        policy: SqsExtendedClientProperties.Policy,
        queueUrl: String,
    ): SqsExtendedClient = SqsExtendedClient(
        sqsOperations = sqs,
        s3Operations = s3,
        boundedS3Operations = null,
        s3MetadataOperations = null,
        encryptedS3Operations = encrypted,
        encryptionIdentity = identity,
        properties = SqsExtendedClientProperties(
            enabled = true,
            producerEnabled = true,
            consumerEnabled = true,
            queues = mapOf("queue" to SqsExtendedClientProperties.QueuePolicy(queueUrl, policy)),
            security = SqsExtendedClientProperties.Security(
                mapOf(
                    policy.pointerSigningKeyRef to
                            Base64.getUrlEncoder().withoutPadding().encodeToString(signingKey()),
                ),
            ),
        ),
    )

    private fun policy() = SqsExtendedClientProperties.Policy(
        bucket = "bucket-${Base58.randomString(16)}",
        offloadThresholdBytes = 32,
        maxInlineBytes = 128,
        maxOffloadPayloadBytes = 1_048_576,
        configuredSqsRetentionSeconds = 14 * 3_600,
        configuredMaxVisibilityRetryWindowSeconds = 7 * 3_600,
        rollbackDeadlineSeconds = 24 * 3_600,
        encryption = SqsExtendedClientProperties.Encryption(
            enabled = true,
            encryptionContext = mapOf("purpose" to "extended-client"),
            keyFingerprint = "fingerprint",
        ),
    )

    private fun queueUrl(): String =
        "https://sqs.us-east-1.amazonaws.com/123456789012/${Base58.randomString(16)}"

    private fun signingKey(): ByteArray = ByteArray(32) { index -> index.toByte() }
}
