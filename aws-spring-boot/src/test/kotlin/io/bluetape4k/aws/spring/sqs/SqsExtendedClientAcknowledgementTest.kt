package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.codec.Base58
import io.bluetape4k.aws.spring.s3.S3BoundedObjectReadOperations
import io.bluetape4k.aws.spring.s3.S3HeadMetadata
import io.bluetape4k.aws.spring.s3.S3ObjectMetadataOperations
import io.bluetape4k.aws.spring.s3.S3Operations
import io.bluetape4k.aws.spring.s3.S3PutIfAbsentResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse
import software.amazon.awssdk.services.sqs.model.Message
import java.util.Base64

class SqsExtendedClientAcknowledgementTest {

    @Test
    fun `SQS delete precedes marker and payload cleanup`() = runTest {
        val queueUrl = queueUrl()
        val policy = policy(deleteOnAck = true)
        val sqs = mockk<SqsFullRequestOperations>()
        val s3 = mockk<S3Operations>()
        val metadata = mockk<S3ObjectMetadataOperations>()
        val bounded = mockk<S3BoundedObjectReadOperations>()
        val events = mutableListOf<String>()
        val client = client(sqs, s3, metadata, bounded, policy, queueUrl)
        val message = receivePointer(client, sqs, bounded, policy, queueUrl)
        coEvery { sqs.delete(queueUrl, message.rawReceiptHandle()) } answers {
            events += "sqs-delete"
            DeleteMessageResponse.builder().build()
        }
        coEvery { metadata.headObjectWithMetadata(policy.bucket, any()) } throws IllegalStateException("missing")
        coEvery {
            metadata.putObjectIfAbsentWithMetadata(policy.bucket, any(), any(), any(), any())
        } answers {
            events += "marker-create"
            S3PutIfAbsentResult.Created
        }
        coEvery { s3.delete(policy.bucket, any()) } answers {
            events += "payload-delete"
            DeleteObjectResponse.builder().build()
        }

        val result = client.acknowledge(message)

        result.sqsDeleted shouldBeEqualTo true
        result.payloadDeleted shouldBeEqualTo true
        result.cleanupRequired shouldBeEqualTo false
        events shouldBeEqualTo listOf("sqs-delete", "marker-create", "payload-delete")
    }

    @Test
    fun `SQS acknowledgement failure never deletes payload`() = runTest {
        val queueUrl = queueUrl()
        val policy = policy(deleteOnAck = true)
        val sqs = mockk<SqsFullRequestOperations>()
        val s3 = mockk<S3Operations>()
        val metadata = mockk<S3ObjectMetadataOperations>()
        val bounded = mockk<S3BoundedObjectReadOperations>()
        val client = client(sqs, s3, metadata, bounded, policy, queueUrl)
        val message = receivePointer(client, sqs, bounded, policy, queueUrl)
        coEvery { sqs.delete(queueUrl, message.rawReceiptHandle()) } throws IllegalStateException("sqs-secret")

        val error = assertFailsWith<SqsExtendedAcknowledgementException> {
            client.acknowledge(message)
        }

        error.sqsDeleted shouldBeEqualTo false
        error.toString() shouldNotContain "sqs-secret"
        coVerify(exactly = 0) { s3.delete(any(), any()) }
        coVerify(exactly = 0) { metadata.putObjectIfAbsentWithMetadata(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `deleteOnAck false leaves payload lifecycle owned`() = runTest {
        val queueUrl = queueUrl()
        val policy = policy(deleteOnAck = false)
        val sqs = mockk<SqsFullRequestOperations>()
        val s3 = mockk<S3Operations>()
        val metadata = mockk<S3ObjectMetadataOperations>()
        val bounded = mockk<S3BoundedObjectReadOperations>()
        val client = client(sqs, s3, metadata, bounded, policy, queueUrl)
        val message = receivePointer(client, sqs, bounded, policy, queueUrl)
        coEvery { sqs.delete(queueUrl, message.rawReceiptHandle()) } returns DeleteMessageResponse.builder().build()

        val result = client.acknowledge(message)

        result.sqsDeleted shouldBeEqualTo true
        result.payloadDeleted shouldBeEqualTo false
        result.cleanupRequired shouldBeEqualTo false
        result.cleanupHandle.shouldBeNull()
        coVerify(exactly = 0) { s3.delete(any(), any()) }
        coVerify(exactly = 0) { metadata.headObjectWithMetadata(any(), any()) }
    }

    @Test
    fun `payload delete failure returns opaque retry handle`() = runTest {
        val queueUrl = queueUrl()
        val policy = policy(deleteOnAck = true)
        val sqs = mockk<SqsFullRequestOperations>()
        val s3 = mockk<S3Operations>()
        val metadata = mockk<S3ObjectMetadataOperations>()
        val bounded = mockk<S3BoundedObjectReadOperations>()
        val client = client(sqs, s3, metadata, bounded, policy, queueUrl)
        val message = receivePointer(client, sqs, bounded, policy, queueUrl)
        coEvery { sqs.delete(queueUrl, message.rawReceiptHandle()) } returns DeleteMessageResponse.builder().build()
        coEvery { metadata.headObjectWithMetadata(policy.bucket, any()) } throws IllegalStateException("missing")
        coEvery {
            metadata.putObjectIfAbsentWithMetadata(policy.bucket, any(), any(), any(), any())
        } returns S3PutIfAbsentResult.Created
        coEvery { s3.delete(policy.bucket, any()) } throws IllegalStateException("delete-secret")

        val result = client.acknowledge(message)

        result.sqsDeleted shouldBeEqualTo true
        result.payloadDeleted shouldBeEqualTo false
        result.cleanupRequired shouldBeEqualTo true
        result.cleanupHandle.toString() shouldBeEqualTo "SqsExtendedCleanupHandle(available=true)"
        result.toString() shouldNotContain "delete-secret"
    }

    @Test
    fun `cleanup retries marker verified payload deletion with same handle`() = runTest {
        val queueUrl = queueUrl()
        val policy = policy(deleteOnAck = true)
        val sqs = mockk<SqsFullRequestOperations>()
        val s3 = mockk<S3Operations>()
        val metadata = mockk<S3ObjectMetadataOperations>()
        val bounded = mockk<S3BoundedObjectReadOperations>()
        val client = client(sqs, s3, metadata, bounded, policy, queueUrl)
        val message = receivePointer(client, sqs, bounded, policy, queueUrl)
        coEvery { sqs.delete(queueUrl, message.rawReceiptHandle()) } returns DeleteMessageResponse.builder().build()
        coEvery { metadata.headObjectWithMetadata(policy.bucket, any()) } throws IllegalStateException("missing")
        coEvery {
            metadata.putObjectIfAbsentWithMetadata(policy.bucket, any(), any(), any(), any())
        } returns S3PutIfAbsentResult.Created
        coEvery { s3.delete(policy.bucket, any()) } throws IllegalStateException("delete-secret")

        val acknowledgement = client.acknowledge(message)
        val handle = requireNotNull(acknowledgement.cleanupHandle)
        val expectedMetadata = mapOf(
            "bt4k-marker-version" to "1",
            "bt4k-pointer-digest" to requireNotNull(handle.pointerDigest),
            "bt4k-policy-fingerprint" to handle.policyFingerprint,
            "bt4k-queue-url-digest" to sha256(queueUrl),
        )
        coEvery { metadata.headObjectWithMetadata(policy.bucket, requireNotNull(handle.markerKey)) } returns
            S3HeadMetadata(0, null, "application/octet-stream", expectedMetadata)
        coEvery { s3.delete(policy.bucket, handle.pointer.key) } returns DeleteObjectResponse.builder().build()

        val cleanup = client.cleanup(handle)

        cleanup.deleted shouldBeEqualTo true
        cleanup.cleanupRequired shouldBeEqualTo false
        cleanup.cleanupHandle.shouldBeNull()
        coVerify(exactly = 2) { s3.delete(policy.bucket, handle.pointer.key) }
    }

    @Test
    fun `foreign marker blocks payload deletion`() = runTest {
        val queueUrl = queueUrl()
        val policy = policy(deleteOnAck = true)
        val sqs = mockk<SqsFullRequestOperations>()
        val s3 = mockk<S3Operations>()
        val metadata = mockk<S3ObjectMetadataOperations>()
        val bounded = mockk<S3BoundedObjectReadOperations>()
        val client = client(sqs, s3, metadata, bounded, policy, queueUrl)
        val message = receivePointer(client, sqs, bounded, policy, queueUrl)
        coEvery { sqs.delete(queueUrl, message.rawReceiptHandle()) } returns DeleteMessageResponse.builder().build()
        coEvery { metadata.headObjectWithMetadata(policy.bucket, any()) } returns
            S3HeadMetadata(0, null, "application/octet-stream", mapOf("bt4k-marker-version" to "foreign"))

        assertFailsWith<SqsExtendedCleanupException> { client.acknowledge(message) }

        coVerify(exactly = 0) { s3.delete(any(), any()) }
    }

    private suspend fun receivePointer(
        client: SqsExtendedClient,
        sqs: SqsFullRequestOperations,
        bounded: S3BoundedObjectReadOperations,
        policy: SqsExtendedClientProperties.Policy,
        queueUrl: String,
    ): SqsExtendedReceivedMessage {
        val pointer = SqsExtendedPointerCodec.encode(
            bucket = policy.bucket,
            key = "${policy.normalizedKeyPrefix()}payload/${Base58.randomString(16)}",
            contentType = "application/json",
            encrypted = false,
            queueUrl = queueUrl,
            policyFingerprint = client.policyFingerprint(queueUrl),
            signingKey = signingKey(),
        )
        coEvery { sqs.receive(queueUrl, 1, 20, policy.minimumVisibilityTimeoutSeconds) } returns
            listOf(received(queueUrl, pointer))
        coEvery { bounded.downloadBytesBounded(policy.bucket, any(), policy.maxOffloadPayloadBytes) } returns
            "payload-${Base58.randomString(16)}".encodeToByteArray()
        return client.receive(queueUrl, visibilityTimeoutSeconds = policy.minimumVisibilityTimeoutSeconds).single()
    }

    private fun client(
        sqs: SqsFullRequestOperations,
        s3: S3Operations,
        metadata: S3ObjectMetadataOperations,
        bounded: S3BoundedObjectReadOperations,
        policy: SqsExtendedClientProperties.Policy,
        queueUrl: String,
    ): SqsExtendedClient = SqsExtendedClient(
        sqsOperations = sqs,
        s3Operations = s3,
        boundedS3Operations = bounded,
        s3MetadataOperations = metadata,
        encryptedS3Operations = null,
        encryptionIdentity = null,
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

    private fun policy(deleteOnAck: Boolean) = SqsExtendedClientProperties.Policy(
        bucket = "bucket-${Base58.randomString(16)}",
        offloadThresholdBytes = 32,
        maxInlineBytes = 128,
        maxOffloadPayloadBytes = 1_024,
        deleteOnAck = deleteOnAck,
        configuredSqsRetentionSeconds = 14 * 3_600,
        configuredMaxVisibilityRetryWindowSeconds = 7 * 3_600,
        rollbackDeadlineSeconds = 24 * 3_600,
    )

    private fun queueUrl(): String =
        "https://sqs.us-east-1.amazonaws.com/123456789012/${Base58.randomString(16)}"

    private fun signingKey(): ByteArray = ByteArray(32) { index -> index.toByte() }

    private fun sha256(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()),
        )

    private fun received(queueUrl: String, body: String): SqsReceivedMessage =
        SqsReceivedMessage(
            queueUrl = queueUrl,
            message = Message.builder()
                .messageId("message-${Base58.randomString(16)}")
                .receiptHandle("receipt-${Base58.randomString(16)}")
                .body(body)
                .build(),
        )

    private fun SqsExtendedReceivedMessage.rawReceiptHandle(): String = rawMessage.receiptHandle
}
