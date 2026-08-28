package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.aws.spring.observability.AwsMicrometerSupport
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.ObjectStreamClass
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream

class SqsObservationContextTest {

    @Test
    fun `observation properties are disabled by default and serializable contracts use uid one`() {
        SqsObservationProperties().enabled.shouldBeFalse()
        serialVersionUid(SqsObservationProperties::class.java) shouldBeEqualTo 1L
        serialVersionUid(SqsObservationMetadata::class.java) shouldBeEqualTo 1L
    }

    @Test
    fun `observation enums expose the complete bounded value sets`() {
        SqsObservationStage.entries shouldBeEqualTo listOf(
            SqsObservationStage.RECEIVE,
            SqsObservationStage.PROCESS,
            SqsObservationStage.ACKNOWLEDGEMENT,
        )
        SqsObservationOutcome.entries shouldBeEqualTo listOf(
            SqsObservationOutcome.UNKNOWN,
            SqsObservationOutcome.SUCCESS,
            SqsObservationOutcome.RETRIED,
            SqsObservationOutcome.ERROR,
            SqsObservationOutcome.CANCELLED,
            SqsObservationOutcome.PARTIAL,
        )
        SqsObservationDelivery.entries shouldBeEqualTo listOf(
            SqsObservationDelivery.UNKNOWN,
            SqsObservationDelivery.FIRST,
            SqsObservationDelivery.REDELIVERED,
        )
    }

    @Test
    fun `receive metadata may omit attempt while process and acknowledgement require a positive attempt`() {
        observationMetadata(stage = SqsObservationStage.RECEIVE).initialAttempt.shouldBeNull()
        observationMetadata(stage = SqsObservationStage.RECEIVE, initialAttempt = 1).initialAttempt shouldBeEqualTo 1
        observationMetadata(stage = SqsObservationStage.PROCESS, initialAttempt = 1).initialAttempt shouldBeEqualTo 1
        observationMetadata(
            stage = SqsObservationStage.ACKNOWLEDGEMENT,
            initialAttempt = 1,
        ).initialAttempt shouldBeEqualTo 1

        listOf(SqsObservationStage.PROCESS, SqsObservationStage.ACKNOWLEDGEMENT).forEach { stage ->
            assertFailsWith<IllegalArgumentException> {
                observationMetadata(stage = stage)
            }
            assertFailsWith<IllegalArgumentException> {
                observationMetadata(stage = stage, initialAttempt = 0)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            observationMetadata(stage = SqsObservationStage.RECEIVE, initialAttempt = 0)
        }
    }

    @Test
    fun `batch metadata suppresses message and fifo identifiers even for a single message batch`() {
        val metadata = observationMetadata(
            batch = true,
            batchSize = 1,
            messageId = "message-id",
            messageGroupId = "group-id",
            messageDeduplicationId = "deduplication-id",
        )

        metadata.batch.shouldBeTrue()
        metadata.batchSize shouldBeEqualTo 1
        metadata.messageId.shouldBeNull()
        metadata.messageGroupId.shouldBeNull()
        metadata.messageDeduplicationId.shouldBeNull()
    }

    @ParameterizedTest(name = "receive count {0} maps to {1}")
    @MethodSource("receiveCountCases")
    fun `receive count parsing maps missing invalid and redelivery boundaries`(
        receiveCount: String?,
        expected: SqsObservationDelivery,
    ) {
        resolveSqsObservationDelivery(receiveCount) shouldBeEqualTo expected
    }

    @Test
    fun `observation context exposes bounded mutable state through the public attempt getter`() {
        val context = SqsObservationContext(
            observationMetadata(
                stage = SqsObservationStage.PROCESS,
                initialAttempt = 1,
            )
        )

        context.attempt shouldBeEqualTo 1
        context.currentAttempt = 2
        context.attempt shouldBeEqualTo 2
        context.outcome = SqsObservationOutcome.RETRIED
        context.retryCount = 1
        context.acknowledgementSuccessCount = 1
        context.acknowledgementFailureCount = 0
        context.failureStage = "process"

        context.outcome shouldBeEqualTo SqsObservationOutcome.RETRIED
        context.retryCount shouldBeEqualTo 1
        context.acknowledgementSuccessCount shouldBeEqualTo 1
        context.acknowledgementFailureCount shouldBeEqualTo 0
        context.failureStage shouldBeEqualTo "process"
    }

    @ParameterizedTest(name = "queue input {0}")
    @MethodSource("queueNameCases")
    fun `metadata accepts only an allowlisted raw final queue URL segment`(
        queueInput: String?,
        expectedQueueName: String,
    ) {
        val metadata = observationMetadata(queueName = queueInput.orEmpty())
        val context = SqsObservationContext(metadata)

        metadata.queueName shouldBeEqualTo expectedQueueName
        resolveSqsObservationQueueName(queueInput) shouldBeEqualTo expectedQueueName
        context.metadata.queueName shouldBeEqualTo expectedQueueName
    }

    @Test
    fun `observation-local sanitizer is stricter than AwsMicrometerSupport_queueNameTag`() {
        val resolvedUrl = "https://user:secret@host/123456789012/orders?token=secret#fragment"
        val legacyQueueName = AwsMicrometerSupport.queueNameTag(resolvedUrl).value
        val metadata = observationMetadata(queueName = resolvedUrl)

        legacyQueueName shouldContain "orders?token=secret#fragment"
        metadata.queueName shouldBeEqualTo "orders"
        metadata.toString() shouldNotContain "https://"
        metadata.toString() shouldNotContain "secret"
        metadata.toString() shouldNotContain "123456789012"
        metadata.toString() shouldNotContain "token"
        metadata.toString() shouldNotContain "fragment"
    }

    @Test
    fun `sanitized queue name can be resolved once and reused as immutable metadata input`() {
        val resolvedQueueName = resolveSqsObservationQueueName("https://host/orders.fifo")

        resolvedQueueName shouldBeEqualTo "orders.fifo"
        observationMetadata(queueName = resolvedQueueName).queueName shouldBeEqualTo resolvedQueueName
    }

    @Test
    fun `queue name cache sanitizes one resolved URL once across receive and process lookups`() {
        val sanitizerCalls = AtomicInteger()
        val cache = SqsObservationQueueNameCache { queueUrl ->
            sanitizerCalls.incrementAndGet()
            resolveSqsObservationQueueName(queueUrl)
        }

        repeat(11) {
            cache.resolve("https://host/123456789012/orders") shouldBeEqualTo "orders"
        }

        sanitizerCalls.get() shouldBeEqualTo 1
    }

    @Test
    fun `blank listener and queue names become unknown without deriving listener id from message data`() {
        val metadata = observationMetadata(
            listenerId = " \t\n",
            queueName = "  ",
            messageId = "body-message-id",
            messageGroupId = "body-group-id",
            messageDeduplicationId = "body-deduplication-id",
        )

        metadata.listenerId shouldBeEqualTo "unknown"
        metadata.queueName shouldBeEqualTo "unknown"
        metadata.toString() shouldNotContain "body-message-id"
        metadata.toString() shouldNotContain "body-group-id"
        metadata.toString() shouldNotContain "body-deduplication-id"
    }

    @Test
    fun `metadata string representation excludes identifiers attempt and message-bearing values`() {
        val metadata = observationMetadata(
            stage = SqsObservationStage.PROCESS,
            initialAttempt = 7,
            messageId = "message-id",
            messageGroupId = "message-group-id",
            messageDeduplicationId = "message-deduplication-id",
        )
        val text = metadata.toString()

        text shouldContain "listener-1"
        text shouldContain "orders"
        text shouldContain "PROCESS"
        text shouldNotContain "message-id"
        text shouldNotContain "message-group-id"
        text shouldNotContain "message-deduplication-id"
        text shouldNotContain "7"
        text shouldNotContain "https://"
        text shouldNotContain "receipt-handle"
        text shouldNotContain "message-body"
    }

    private fun observationMetadata(
        listenerId: String = "listener-1",
        queueName: String = "orders",
        stage: SqsObservationStage = SqsObservationStage.RECEIVE,
        batch: Boolean = false,
        messageId: String? = "message-id",
        messageGroupId: String? = "message-group-id",
        messageDeduplicationId: String? = "message-deduplication-id",
        initialAttempt: Int? = null,
        batchSize: Int = 0,
    ): SqsObservationMetadata = SqsObservationMetadata(
        listenerId = listenerId,
        queueName = queueName,
        stage = stage,
        batch = batch,
        messageId = messageId,
        messageGroupId = messageGroupId,
        messageDeduplicationId = messageDeduplicationId,
        initialAttempt = initialAttempt,
        batchSize = batchSize,
    )

    private fun serialVersionUid(type: Class<*>): Long =
        ObjectStreamClass.lookup(type).serialVersionUID

    companion object {
        @JvmStatic
        fun receiveCountCases(): Stream<Arguments> = Stream.of(
            Arguments.of(null, SqsObservationDelivery.UNKNOWN),
            Arguments.of("", SqsObservationDelivery.UNKNOWN),
            Arguments.of(" ", SqsObservationDelivery.UNKNOWN),
            Arguments.of("not-a-number", SqsObservationDelivery.UNKNOWN),
            Arguments.of("0", SqsObservationDelivery.UNKNOWN),
            Arguments.of("-1", SqsObservationDelivery.UNKNOWN),
            Arguments.of("1", SqsObservationDelivery.FIRST),
            Arguments.of("2", SqsObservationDelivery.REDELIVERED),
            Arguments.of("99", SqsObservationDelivery.REDELIVERED),
        )

        @JvmStatic
        fun queueNameCases(): Stream<Arguments> {
            val eightyCharacters = "a".repeat(80)
            val eightyOneCharacters = "a".repeat(81)
            return Stream.of(
                Arguments.of(null, "unknown"),
                Arguments.of("", "unknown"),
                Arguments.of(" \t\n", "unknown"),
                Arguments.of("orders", "orders"),
                Arguments.of("orders.fifo", "orders.fifo"),
                Arguments.of("https://user:secret@host/123456789012/orders?token=secret#fragment", "orders"),
                Arguments.of("https://host/orders%2Farchive", "unknown"),
                Arguments.of("https://host/orders/", "unknown"),
                Arguments.of("https://host", "unknown"),
                Arguments.of("https://[malformed", "unknown"),
                Arguments.of("https://host/123456789012", "unknown"),
                Arguments.of("https://host/\$orders", "unknown"),
                Arguments.of("https://host/tenant/orders.fifo?token=secret#fragment", "orders.fifo"),
                Arguments.of("https://host?queue=orders", "unknown"),
                Arguments.of("https://host/${eightyCharacters}", eightyCharacters),
                Arguments.of("https://host/${eightyOneCharacters}", "unknown"),
            )
        }
    }
}
