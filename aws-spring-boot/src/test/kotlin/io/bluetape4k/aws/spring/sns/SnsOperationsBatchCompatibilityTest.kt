package io.bluetape4k.aws.spring.sns

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sns.model.ConfirmSubscriptionResponse
import software.amazon.awssdk.services.sns.model.PublishResponse
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class SnsOperationsBatchCompatibilityTest {

    @Test
    fun `default batch fallback is sequential and stops after first failure`() = runTest {
        val operations = RecordingLegacyOperations(failureMessage = "message-2")
        val request = request(3)

        val error = assertFailsWith<SnsBatchTransportException> {
            operations.publishBatch(request, SnsBatchExecutionOptions(maxInFlightBatches = 4))
        }

        error.completedEntryIds shouldBeEqualTo listOf("entry-1")
        error.failureType shouldBeEqualTo SnsBatchFailureType.UNKNOWN
        operations.publishedIds shouldBeEqualTo listOf("message-1", "message-2")
        operations.maxActive shouldBeEqualTo 1
        error.toString() shouldNotContain "secret"
    }

    @Test
    fun `default batch fallback preserves cancellation identity and empty input`() = runSuspendIO {
        val cancellation = CancellationException("cancelled")
        val operations = RecordingLegacyOperations(cancellation = cancellation)

        val error = assertFailsWith<CancellationException> {
            operations.publishBatch(request(1))
        }
        error shouldBeSameInstanceAs cancellation

        val empty = RecordingLegacyOperations().publishBatch(request(0), SnsBatchExecutionOptions(4))
        empty.successful.shouldBeEmpty()
        empty.failed.shouldBeEmpty()
    }

    @Test
    fun `noop and precompiled legacy consumer use the additive default method`() = runSuspendIO {
        val noopResult = NoopSnsOperations.publishBatch(request(1))
        noopResult.successful.single().entryId shouldBeEqualTo "entry-1"

        val fixtureBytes = requireNotNull(javaClass.getResourceAsStream(LEGACY_FIXTURE_RESOURCE)) {
            "legacy fixture resource is missing: $LEGACY_FIXTURE_RESOURCE"
        }.use { it.readBytes() }
        sha256(fixtureBytes) shouldBeEqualTo LEGACY_FIXTURE_SHA256
        val loader = LegacyFixtureClassLoader(javaClass.classLoader, LEGACY_FIXTURE_CLASS_NAME, fixtureBytes)
        val type = loader.loadClass(LEGACY_FIXTURE_CLASS_NAME)
        type.classLoader shouldBeSameInstanceAs loader
        val operations = type.getDeclaredConstructor().newInstance() as SnsOperations
        val result = operations.publishBatch(request(1))
        result.successful.single().messageId shouldBeEqualTo "legacy"
    }

    private fun request(size: Int): SnsPublishBatchRequest =
        SnsPublishBatchRequest(
            topicArn = "arn:aws:sns:us-east-1:000000000000:batch-topic",
            entries = (1..size).map { index ->
                SnsPublishBatchEntry(
                    id = "entry-$index",
                    message = "message-$index",
                )
            },
        )

    private class RecordingLegacyOperations(
        private val failureMessage: String? = null,
        private val cancellation: CancellationException? = null,
    ) : SnsOperations {
        val publishedIds = mutableListOf<String>()
        var maxActive: Int = 0
            private set
        private val active = AtomicInteger()

        override suspend fun createTopic(topicName: String, attributes: Map<String, String>): String = topicName
        override suspend fun createFifoTopic(
            topicName: String,
            contentBasedDeduplication: Boolean,
            fifoThroughputScope: SnsFifoThroughputScope?,
            attributes: Map<String, String>,
        ): String = topicName
        override suspend fun createConfiguredTopic(topicName: String): String = topicName
        override suspend fun findTopicArn(topicName: String): String? = topicName
        override suspend fun publish(request: SnsPublishRequest): PublishResponse {
            val current = active.incrementAndGet()
            maxActive = maxOf(maxActive, current)
            try {
                publishedIds += request.message
                cancellation?.let { throw it }
                if (request.message == failureMessage) {
                    throw IllegalStateException("secret transport failure")
                }
                delay(1)
                return PublishResponse.builder().messageId(request.message).build()
            } finally {
                active.decrementAndGet()
            }
        }
        override suspend fun publishSms(request: SnsSmsRequest): PublishResponse = PublishResponse.builder().build()
        override suspend fun confirmSubscription(
            topicArn: String,
            token: String,
            authenticateOnUnsubscribe: Boolean,
        ): ConfirmSubscriptionResponse = ConfirmSubscriptionResponse.builder().build()
        override suspend fun confirmSubscription(
            message: SnsHttpMessage,
            authenticateOnUnsubscribe: Boolean,
        ): ConfirmSubscriptionResponse = ConfirmSubscriptionResponse.builder().build()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val LEGACY_FIXTURE_RESOURCE =
            "/sns-abi/io/bluetape4k/aws/spring/sns/consumer/LegacySnsOperationsFixture.class"
        const val LEGACY_FIXTURE_CLASS_NAME =
            "io.bluetape4k.aws.spring.sns.consumer.LegacySnsOperationsFixture"
        const val LEGACY_FIXTURE_SHA256 =
            "b8814d524f38f624ad8c51401286a694d64785ab352ecc1d301d186711c7d177"
    }
}

private class LegacyFixtureClassLoader(
    parent: ClassLoader,
    private val fixtureClassName: String,
    private val fixtureBytes: ByteArray,
) : ClassLoader(parent) {

    protected override fun findClass(name: String): Class<*> =
        if (name == fixtureClassName) {
            defineClass(name, fixtureBytes, 0, fixtureBytes.size)
        } else {
            super.findClass(name)
        }
}
