package io.bluetape4k.aws.spring.sns

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

class SnsBatchModelsTest {

    @Test
    fun `six public models are serializable and preserve values`() {
        val entry = SnsPublishBatchEntry(
            id = "entry-secret",
            message = "payload-secret",
            subject = "subject-secret",
        )
        val request = SnsPublishBatchRequest(
            topicArn = "arn:aws:sns:ap-northeast-2:000000000000:topic-secret",
            entries = listOf(entry),
        )
        val success = SnsPublishBatchSuccess("entry-secret", "message-secret", "sequence-secret")
        val failure = SnsPublishBatchFailure("entry-failed", "AccessDenied", "raw-failure", true)
        val result = SnsPublishBatchResult(listOf(success), listOf(failure))
        val options = SnsBatchExecutionOptions(maxInFlightBatches = 4)

        listOf(entry, request, result, success, failure, options).forEach { model ->
            model.shouldBeInstanceOf<Serializable>()
            roundTrip(model) shouldBeEqualTo model
        }

        result.isFullySuccessful.shouldBeFalse()
        SnsPublishBatchResult(listOf(success), emptyList()).isFullySuccessful.shouldBeTrue()

        val rendered = "$entry$request$result$success$failure"
        listOf("entry-secret", "payload-secret", "topic-secret", "message-secret", "raw-failure")
            .forEach { secret -> check(secret !in rendered) }
    }

    @Test
    fun `models snapshot mutable collections and expose stable serialVersionUID`() {
        val attributes = mutableMapOf(
            "attribute-secret" to MessageAttributeValue.builder()
                .dataType("String")
                .stringValue("value-secret")
                .build(),
        )
        val entries = mutableListOf(SnsPublishBatchEntry("entry-1", "message-1", messageAttributes = attributes))
        val entry = entries.single()
        val request = SnsPublishBatchRequest("arn:aws:sns:region:account:topic", entries)

        attributes.clear()
        entries.clear()

        entry.messageAttributes shouldHaveSize 1
        request.entries shouldHaveSize 1

        listOf(
            SnsPublishBatchEntry::class.java,
            SnsPublishBatchRequest::class.java,
            SnsPublishBatchResult::class.java,
            SnsPublishBatchSuccess::class.java,
            SnsPublishBatchFailure::class.java,
            SnsBatchExecutionOptions::class.java,
        ).forEach { type ->
            val field = type.getDeclaredField("serialVersionUID")
            field.isAccessible = true
            field.getLong(null) shouldBeEqualTo 1L
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> roundTrip(value: T): T {
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { it.writeObject(value) }
        return ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use { it.readObject() as T }
    }
}
