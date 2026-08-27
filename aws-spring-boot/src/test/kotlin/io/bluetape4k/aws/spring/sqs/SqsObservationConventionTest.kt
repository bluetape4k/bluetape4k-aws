package io.bluetape4k.aws.spring.sqs

import io.micrometer.common.KeyValues
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SqsObservationConventionTest {

    @Test
    fun `default conventions expose only the three bounded names`() {
        val conventions = defaultSqsObservationConventions()

        assertEquals(SqsObservationStage.entries.toSet(), conventions.keys)
        assertEquals(
            setOf(
                SQS_RECEIVE_OBSERVATION_NAME,
                SQS_PROCESS_OBSERVATION_NAME,
                SQS_ACKNOWLEDGEMENT_OBSERVATION_NAME,
            ),
            conventions.values.mapTo(mutableSetOf()) { it.name },
        )
    }

    @Test
    fun `low cardinality keys and values stay inside the allowlist`() {
        val context = SqsObservationContext(
            metadata(
                stage = SqsObservationStage.ACKNOWLEDGEMENT,
                batch = true,
                batchSize = 7,
                initialAttempt = 1,
                acknowledgementAction = SqsAcknowledgementAction.CHANGE_VISIBILITY,
                delivery = SqsObservationDelivery.REDELIVERED,
            ),
        ).apply {
            outcome = SqsObservationOutcome.PARTIAL
            failureStage = "acknowledgement"
        }
        val convention = defaultSqsObservationConventions().getValue(SqsObservationStage.ACKNOWLEDGEMENT)

        assertEquals(
            mapOf(
                "messaging.system" to "sqs",
                "messaging.operation" to "acknowledgement",
                "messaging.destination.name" to "orders",
                "bluetape4k.aws.sqs.listener.id" to "listener-1",
                "bluetape4k.aws.sqs.outcome" to "partial",
                "bluetape4k.aws.sqs.ack.action" to "change_visibility",
                "bluetape4k.aws.sqs.batch.size" to "6-10",
                "bluetape4k.aws.sqs.delivery" to "unknown",
                "bluetape4k.aws.sqs.failure.stage" to "acknowledgement",
            ),
            convention.getLowCardinalityKeyValues(context).asMap(),
        )
        assertEquals("orders change_visibility", convention.getContextualName(context))
    }

    @Test
    fun `terminal mutable context values are read when key values are requested`() {
        val context = SqsObservationContext(
            metadata(stage = SqsObservationStage.PROCESS, initialAttempt = 1),
        )
        val convention = defaultSqsObservationConventions().getValue(SqsObservationStage.PROCESS)

        assertEquals("unknown", convention.getLowCardinalityKeyValues(context).asMap()["bluetape4k.aws.sqs.outcome"])
        assertEquals("1", convention.getHighCardinalityKeyValues(context).asMap()["bluetape4k.aws.sqs.attempt"])

        context.outcome = SqsObservationOutcome.RETRIED
        context.currentAttempt = 2

        assertEquals("retried", convention.getLowCardinalityKeyValues(context).asMap()["bluetape4k.aws.sqs.outcome"])
        assertEquals("2", convention.getHighCardinalityKeyValues(context).asMap()["bluetape4k.aws.sqs.attempt"])
    }

    @Test
    fun `high cardinality keys are bounded and batch suppresses every message identifier`() {
        val single = SqsObservationContext(
            metadata(stage = SqsObservationStage.PROCESS, initialAttempt = 3),
        )
        val batch = SqsObservationContext(
            metadata(stage = SqsObservationStage.PROCESS, batch = true, batchSize = 1, initialAttempt = 1),
        )
        val receive = SqsObservationContext(
            metadata(stage = SqsObservationStage.RECEIVE, initialAttempt = 1),
        )
        val convention = defaultSqsObservationConventions().getValue(SqsObservationStage.PROCESS)
        val receiveConvention = defaultSqsObservationConventions().getValue(SqsObservationStage.RECEIVE)

        assertEquals(
            mapOf(
                "messaging.message.id" to "message-id",
                "messaging.sqs.message.group.id" to "group-id",
                "messaging.sqs.message.deduplication.id" to "dedup-id",
                "bluetape4k.aws.sqs.attempt" to "3",
            ),
            convention.getHighCardinalityKeyValues(single).asMap(),
        )
        assertTrue(convention.getHighCardinalityKeyValues(batch).asMap().isEmpty())
        assertTrue(receiveConvention.getHighCardinalityKeyValues(receive).asMap().isEmpty())
    }

    @Test
    fun `receive and batch delivery are always unknown`() {
        val receive = SqsObservationContext(
            metadata(stage = SqsObservationStage.RECEIVE, delivery = SqsObservationDelivery.REDELIVERED),
        )
        val batch = SqsObservationContext(
            metadata(
                stage = SqsObservationStage.PROCESS,
                batch = true,
                batchSize = 2,
                initialAttempt = 1,
                delivery = SqsObservationDelivery.FIRST,
            ),
        )
        val conventions = defaultSqsObservationConventions()

        assertEquals(
            "unknown",
            conventions.getValue(SqsObservationStage.RECEIVE)
                .getLowCardinalityKeyValues(receive).asMap()["bluetape4k.aws.sqs.delivery"],
        )
        assertEquals(
            "unknown",
            conventions.getValue(SqsObservationStage.PROCESS)
                .getLowCardinalityKeyValues(batch).asMap()["bluetape4k.aws.sqs.delivery"],
        )
    }

    @Test
    fun `batch bucket and failure stage values remain bounded`() {
        val convention = defaultSqsObservationConventions().getValue(SqsObservationStage.RECEIVE)
        val expectedBuckets = mapOf(0 to "0", 1 to "1", 2 to "2-5", 5 to "2-5", 6 to "6-10", 10 to "6-10")

        expectedBuckets.forEach { (size, expected) ->
            val context = SqsObservationContext(metadata(batch = size > 0, batchSize = size))
            val batchSize = convention.getLowCardinalityKeyValues(context)
                .asMap()["bluetape4k.aws.sqs.batch.size"]
            assertEquals(expected, batchSize)
        }

        listOf("receive", "conversion", "handler", "acknowledgement", "observation").forEach { stage ->
            val context = SqsObservationContext(metadata()).apply { failureStage = stage }
            val failureStage = convention.getLowCardinalityKeyValues(context)
                .asMap()["bluetape4k.aws.sqs.failure.stage"]
            assertEquals(stage, failureStage)
        }
        val invalid = SqsObservationContext(metadata()).apply { failureStage = "secret-stage" }
        val failureStage = convention.getLowCardinalityKeyValues(invalid)
            .asMap()["bluetape4k.aws.sqs.failure.stage"]
        assertEquals("observation", failureStage)
    }

    @Test
    fun `duplicate user convention for the same stage fails deterministically`() {
        val first = TestConvention(SqsObservationStage.PROCESS)
        val second = TestConvention(SqsObservationStage.PROCESS)

        val error = assertThrows(IllegalStateException::class.java) {
            resolveSqsObservationConventions(listOf(first, second))
        }

        assertTrue(error.message.orEmpty().contains("PROCESS"))
    }

    @Test
    fun `user convention replaces only its own stage`() {
        val user = TestConvention(SqsObservationStage.PROCESS)
        val conventions = resolveSqsObservationConventions(listOf(user))

        assertTrue(conventions.getValue(SqsObservationStage.PROCESS) === user)
        assertFalse(conventions.getValue(SqsObservationStage.RECEIVE) === user)
        assertFalse(conventions.getValue(SqsObservationStage.ACKNOWLEDGEMENT) === user)
    }

    @Test
    fun `tags and contextual name contain no raw url receipt body or arbitrary attributes`() {
        val source = "https://user:secret@host/123456789012/orders?token=secret#fragment"
        val context = SqsObservationContext(
            metadata(queueName = source, stage = SqsObservationStage.PROCESS, initialAttempt = 1),
        )
        val convention = defaultSqsObservationConventions().getValue(SqsObservationStage.PROCESS)
        val text = buildString {
            append(convention.getContextualName(context))
            append(convention.getLowCardinalityKeyValues(context))
            append(convention.getHighCardinalityKeyValues(context))
            append(context)
        }

        listOf("https://", "user", "secret", "host", "123456789012", "token", "fragment", "receipt", "body")
            .forEach { assertFalse(text.contains(it)) }
    }

    private fun metadata(
        stage: SqsObservationStage = SqsObservationStage.RECEIVE,
        queueName: String = "orders",
        batch: Boolean = false,
        batchSize: Int = 0,
        initialAttempt: Int? = null,
        acknowledgementAction: SqsAcknowledgementAction? = null,
        delivery: SqsObservationDelivery = SqsObservationDelivery.UNKNOWN,
    ): SqsObservationMetadata = SqsObservationMetadata(
        listenerId = "listener-1",
        queueName = queueName,
        stage = stage,
        batch = batch,
        messageId = "message-id",
        messageGroupId = "group-id",
        messageDeduplicationId = "dedup-id",
        initialAttempt = initialAttempt,
        batchSize = batchSize,
        acknowledgementAction = acknowledgementAction,
        delivery = delivery,
    )

    private class TestConvention(
        override val stage: SqsObservationStage,
    ) : SqsObservationConvention
}

private fun KeyValues.asMap(): Map<String, String> = associate { it.key to it.value }
