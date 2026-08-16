package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.codec.Base58
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.lang.reflect.Modifier

class SqsAutomaticBatchModelsTest {

    @Test
    fun `batch models snapshot mutable input and calculate status`() {
        val sendEntryId = entryId("send")
        val deleteEntryId = entryId("delete")
        val failedEntryId = entryId("failed")
        val attributes = mutableMapOf(
            "attribute-secret" to MessageAttributeValue.builder()
                .dataType("String")
                .stringValue("attribute-value-secret")
                .build(),
        )
        val sendEntry = SqsBatchSendEntry(
            sendEntryId,
            SqsSendRequest(
                queueUrl = "https://sqs.local/queue-secret",
                body = "body-secret",
                messageAttributes = attributes,
            ),
        )
        val success = SqsBatchSendSuccess(sendEntryId, "message-secret", "sequence-secret")
        val failure = SqsBatchEntryFailure(failedEntryId, SqsBatchFailureKind.SERVICE, "AccessDenied")
        val successful = mutableListOf(success)
        val failed = mutableListOf(failure)
        val sendResult = SqsSendManyResult(successful, failed)
        val deleteResult = SqsDeleteManyResult(listOf(deleteEntryId), failed)

        attributes.clear()
        successful.clear()
        failed.clear()

        sendEntry.request.messageAttributes shouldHaveSize 1
        sendResult.successful shouldHaveSize 1
        sendResult.failed shouldHaveSize 1
        deleteResult.successfulEntryIds shouldHaveSize 1
        sendResult.status shouldBeEqualTo SqsBatchResultStatus.PARTIAL_FAILURE
        SqsSendManyResult(listOf(success), emptyList()).status shouldBeEqualTo SqsBatchResultStatus.SUCCESS
        SqsSendManyResult(emptyList(), listOf(failure)).status shouldBeEqualTo SqsBatchResultStatus.FAILURE
        SqsSendManyResult(emptyList(), emptyList()).status shouldBeEqualTo SqsBatchResultStatus.SUCCESS
        SqsDeleteManyResult(listOf(deleteEntryId), emptyList()).status shouldBeEqualTo SqsBatchResultStatus.SUCCESS
    }

    @Test
    fun `batch models serialize with stable ABI and redact sensitive values`() {
        val sendEntryId = entryId("send")
        val deleteEntryId = entryId("delete")
        val failedEntryId = entryId("failed")
        val models = automaticBatchModels(sendEntryId, deleteEntryId, failedEntryId)

        models.forEach { model ->
            model.shouldBeInstanceOf<Serializable>()
            roundTrip(model) shouldBeEqualTo model
        }

        listOf(
            SqsBatchSendEntry::class.java,
            SqsBatchDeleteEntry::class.java,
            SqsBatchSendSuccess::class.java,
            SqsBatchEntryFailure::class.java,
            SqsSendManyResult::class.java,
            SqsDeleteManyResult::class.java,
        ).forEach { type ->
            val serialVersionUid = type.getDeclaredField("serialVersionUID").apply { isAccessible = true }
            serialVersionUid.getLong(null) shouldBeEqualTo 1L
            Modifier.isPrivate(type.declaredMethods.single { it.name == "copy" }.modifiers).shouldBeTrue()
        }

        val rendered = models.joinToString()
        rendered shouldNotContain sendEntryId
        rendered shouldNotContain deleteEntryId
        rendered shouldNotContain failedEntryId
        rendered shouldNotContain "body-secret"
        rendered shouldNotContain "queue-secret"
        rendered shouldNotContain "receipt-secret"
        rendered shouldNotContain "message-secret"
        rendered shouldNotContain "sequence-secret"
        rendered shouldNotContain "attribute-secret"
        rendered shouldNotContain "attribute-value-secret"
        rendered shouldNotContain "AccessDenied"
    }

    @Test
    fun `batch models validate identifiers fields and result membership`() {
        assertFailsWith<IllegalArgumentException> {
            SqsBatchSendEntry("", SqsSendRequest("https://sqs.local/queue", "body"))
        }
        assertFailsWith<IllegalArgumentException> {
            SqsBatchSendEntry("entry!", SqsSendRequest("https://sqs.local/queue", "body"))
        }
        assertFailsWith<IllegalArgumentException> {
            SqsBatchSendEntry("a".repeat(81), SqsSendRequest("https://sqs.local/queue", "body"))
        }
        assertFailsWith<IllegalArgumentException> {
            SqsBatchDeleteEntry(entryId("delete"), "", "receipt")
        }
        assertFailsWith<IllegalArgumentException> {
            SqsBatchDeleteEntry(entryId("delete"), "https://sqs.local/queue", "")
        }
        assertFailsWith<IllegalArgumentException> {
            SqsBatchSendSuccess(entryId("success"), "", null)
        }
        assertFailsWith<IllegalArgumentException> {
            SqsBatchSendSuccess(entryId("success"), "message", "")
        }

        val first = SqsBatchSendSuccess(entryId("first"), "message-1", null)
        assertFailsWith<IllegalArgumentException> {
            SqsSendManyResult(listOf(first, first), emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            val duplicateFailure = SqsBatchEntryFailure(
                first.entryId,
                SqsBatchFailureKind.SERVICE,
                "Error",
            )
            SqsSendManyResult(listOf(first), listOf(duplicateFailure))
        }
        SqsBatchEntryFailure(entryId("transport"), SqsBatchFailureKind.TRANSPORT, "raw-code")
            .code.shouldBeNull()
        SqsBatchEntryFailure(entryId("unknown"), SqsBatchFailureKind.SERVICE, "invalid code")
            .code shouldBeEqualTo "UNKNOWN"
        SqsBatchEntryFailure(entryId("valid"), SqsBatchFailureKind.SERVICE, "AccessDenied")
            .code shouldBeEqualTo "AccessDenied"
    }

    @Test
    fun `normalizer preserves input relative order for send and delete`() {
        val first = SqsBatchSendSuccess(entryId("first"), "message-1", null)
        val second = SqsBatchSendSuccess(entryId("second"), "message-2", null)

        val sendOutcomes = listOf(
            SqsBatchOutcome.SendSuccess(second.entryId, second.messageId, second.sequenceNumber),
            SqsBatchOutcome.Failure(
                SqsBatchEntryFailure(first.entryId, SqsBatchFailureKind.SERVICE, "AccessDenied"),
            ),
        )
        val normalized = BatchResultNormalizer.send(listOf(first.entryId, second.entryId), sendOutcomes)
        normalized.successful.map { it.entryId } shouldBeEqualTo listOf(second.entryId)
        normalized.failed.map { it.entryId } shouldBeEqualTo listOf(first.entryId)

        val deleteResult = BatchResultNormalizer.delete(
            expectedEntryIds = listOf(first.entryId, second.entryId),
            outcomes = listOf(
                SqsBatchOutcome.DeleteSuccess(second.entryId),
                SqsBatchOutcome.Failure(
                    SqsBatchEntryFailure(first.entryId, SqsBatchFailureKind.TRANSPORT, null),
                ),
            ),
        )
        deleteResult.successfulEntryIds shouldBeEqualTo listOf(second.entryId)
        deleteResult.failed.map { it.entryId } shouldBeEqualTo listOf(first.entryId)
        deleteResult.status shouldBeEqualTo SqsBatchResultStatus.PARTIAL_FAILURE
        SendBatchFailureStrategy.entries shouldBeEqualTo listOf(
            SendBatchFailureStrategy.RETURN,
            SendBatchFailureStrategy.THROW,
        )
    }

    @Test
    fun `normalizer rejects unknown duplicate and missing outcomes without raw ids`() {
        val firstId = entryId("first")
        val secondId = entryId("second")
        val unknownId = entryId("unknown")
        val error = assertFailsWith<SqsBatchProtocolException> {
            BatchResultNormalizer.send(
                expectedEntryIds = listOf(firstId, secondId),
                outcomes = listOf(
                    SqsBatchOutcome.SendSuccess(firstId, "message-1", null),
                    SqsBatchOutcome.SendSuccess(firstId, "message-2", null),
                    SqsBatchOutcome.Failure(
                        SqsBatchEntryFailure(unknownId, SqsBatchFailureKind.SERVICE, "AccessDenied"),
                    ),
                ),
            )
        }

        error.submittedEntryCount shouldBeEqualTo 2
        error.responseEntryCount shouldBeEqualTo 3
        error.unknownEntryCount shouldBeEqualTo 1
        error.duplicateEntryCount shouldBeEqualTo 1
        error.missingEntryCount shouldBeEqualTo 1
        error.cause.shouldBeNull()
        error.suppressed shouldHaveSize 0
        "${error.message}$error" shouldNotContain firstId
        "${error.message}$error" shouldNotContain secondId
        "${error.message}$error" shouldNotContain unknownId
    }

    @Test
    fun `send normalizer rejects null or blank response identifiers`() {
        val entryId = entryId("response")

        listOf(
            SqsBatchOutcome.SendSuccess(entryId, null, null),
            SqsBatchOutcome.SendSuccess(entryId, "", null),
            SqsBatchOutcome.SendSuccess(entryId, "message", ""),
        ).forEach { outcome ->
            val error = assertFailsWith<SqsBatchProtocolException> {
                BatchResultNormalizer.send(listOf(entryId), listOf(outcome))
            }
            error.cause.shouldBeNull()
            error.suppressed shouldHaveSize 0
            "${error.message}$error" shouldNotContain entryId
        }
    }

    private fun entryId(prefix: String): String = "$prefix-${Base58.randomString(16)}"

    private fun automaticBatchModels(
        sendEntryId: String,
        deleteEntryId: String,
        failedEntryId: String,
    ): List<Serializable> {
        val sendEntry = SqsBatchSendEntry(
            sendEntryId,
            SqsSendRequest(
                queueUrl = "https://sqs.local/queue-secret",
                body = "body-secret",
                messageAttributes = mapOf(
                    "attribute-secret" to MessageAttributeValue.builder()
                        .dataType("String")
                        .stringValue("attribute-value-secret")
                        .build(),
                ),
            ),
        )
        val deleteEntry = SqsBatchDeleteEntry(
            deleteEntryId,
            "https://sqs.local/queue-secret",
            "receipt-secret",
        )
        val success = SqsBatchSendSuccess(sendEntryId, "message-secret", "sequence-secret")
        val failure = SqsBatchEntryFailure(failedEntryId, SqsBatchFailureKind.SERVICE, "AccessDenied")
        return listOf(
            sendEntry,
            deleteEntry,
            success,
            failure,
            SqsSendManyResult(listOf(success), listOf(failure)),
            SqsDeleteManyResult(listOf(deleteEntryId), listOf(failure)),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> roundTrip(value: T): T {
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { it.writeObject(value) }
        return ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use { it.readObject() as T }
    }
}
