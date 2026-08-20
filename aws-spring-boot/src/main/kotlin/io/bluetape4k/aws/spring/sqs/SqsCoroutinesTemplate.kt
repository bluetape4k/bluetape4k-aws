package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.aws.sqs.getQueueUrl
import io.bluetape4k.aws.sqs.send
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.future.await
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityBatchRequestEntry
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityResponse
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import software.amazon.awssdk.services.sqs.model.SendMessageResponse

/**
 * AWS SDK v2 [SqsAsyncClient]를 Coroutines 친화적인 [SqsOperations]로 감싸는 템플릿.
 */
@Suppress("TooManyFunctions")
class SqsCoroutinesTemplate(
    private val sqsAsyncClient: SqsAsyncClient,
    private val properties: SqsProperties,
): SqsFullRequestOperations {

    override suspend fun getQueueUrl(queueName: String): String =
        sqsAsyncClient.getQueueUrl(queueName).queueUrl()

    override suspend fun createQueue(
        queueName: String,
        attributes: Map<QueueAttributeName, String>,
    ): String =
        sqsAsyncClient.createQueue {
            it.queueName(queueName)
            if (attributes.isNotEmpty()) {
                it.attributes(attributes)
            }
        }.await().queueUrl()

    override suspend fun createConfiguredQueue(queueName: String): String {
        val queue = properties.queues[queueName]
        val attributes = buildMap {
            queue?.redrivePolicy?.let {
                put(QueueAttributeName.REDRIVE_POLICY, it.toRedrivePolicyJson())
            }
        }
        return createQueue(queueName, attributes)
    }

    override suspend fun send(
        queueUrl: String,
        body: String,
        delaySeconds: Int?,
    ): SendMessageResponse =
        sqsAsyncClient.send(queueUrl, body, delaySeconds)

    override suspend fun send(request: SqsSendRequest): SendMessageResponse =
        sqsAsyncClient.sendMessage {
            it.queueUrl(request.queueUrl)
            it.messageBody(request.body)
            request.delaySeconds?.let(it::delaySeconds)
            request.messageGroupId?.let(it::messageGroupId)
            request.messageDeduplicationId?.let(it::messageDeduplicationId)
            if (request.messageAttributes.isNotEmpty()) {
                it.messageAttributes(request.messageAttributes)
            }
        }.await()

    override suspend fun receive(
        queueUrl: String,
        maxMessages: Int,
        waitTimeSeconds: Int,
        visibilityTimeoutSeconds: Int?,
    ): List<SqsReceivedMessage> {
        require(maxMessages in 1..10) { "maxMessages must be between 1 and 10." }
        require(waitTimeSeconds in 0..20) { "waitTimeSeconds must be between 0 and 20." }
        visibilityTimeoutSeconds?.let { require(it in 0..43_200) { "visibilityTimeoutSeconds must be between 0 and 43200." } }

        val response = sqsAsyncClient.receiveMessage {
            it.queueUrl(queueUrl)
            it.maxNumberOfMessages(maxMessages)
            it.waitTimeSeconds(waitTimeSeconds)
            it.messageSystemAttributeNames(MessageSystemAttributeName.ALL)
            it.messageAttributeNames("All")
            visibilityTimeoutSeconds?.let(it::visibilityTimeout)
        }.await()

        return response.messages().orEmpty().map { SqsReceivedMessage(queueUrl, it) }
    }

    override suspend fun delete(
        queueUrl: String,
        receiptHandle: String,
    ): DeleteMessageResponse =
        sqsAsyncClient.deleteMessage {
            it.queueUrl(queueUrl)
            it.receiptHandle(receiptHandle)
        }.await()

    override suspend fun deleteBatch(
        queueUrl: String,
        receiptHandles: Collection<String>,
    ): SqsBatchDeleteResult {
        val handles = receiptHandles.toList()
        requireBatchSize(handles.size)
        require(handles.distinct().size == handles.size) {
            "duplicate batch delete receipt handle"
        }
        if (handles.isEmpty()) {
            return SqsBatchDeleteResult(emptyList(), emptyList())
        }

        val entries = handles.mapIndexed { index, receiptHandle ->
            DeleteMessageBatchRequestEntry.builder()
                .id("entry-$index")
                .receiptHandle(receiptHandle)
                .build()
        }
        val response = sqsAsyncClient.deleteMessageBatch {
            it.queueUrl(queueUrl)
            it.entries(entries)
        }.await()
        val expectedIds = handles.indices.map { "entry-$it" }
        val successful = response.successful().orEmpty().map { it.id() }
        val failed = response.failed().orEmpty().map {
            SqsBatchDeleteFailure(it.id(), it.code(), it.message(), it.senderFault())
        }
        validateBatchEntryIds(
            expected = expectedIds,
            actual = successful + failed.map { it.entryId },
        ) { expected, actual -> SqsBatchDeleteProtocolException(expected, actual) }
        val order = expectedIds.withIndex().associate { it.value to it.index }
        return SqsBatchDeleteResult(
            successfulEntryIds = successful.sortedBy { order.getValue(it) },
            failed = failed.sortedBy { order.getValue(it.entryId) },
        )
    }

    override suspend fun changeVisibility(
        queueUrl: String,
        receiptHandle: String,
        timeoutSeconds: Int,
    ): ChangeMessageVisibilityResponse {
        require(timeoutSeconds in 0..43_200) { "timeoutSeconds must be between 0 and 43200." }
        return sqsAsyncClient.changeMessageVisibility {
            it.queueUrl(queueUrl)
            it.receiptHandle(receiptHandle)
            it.visibilityTimeout(timeoutSeconds)
        }.await()
    }

    override suspend fun changeVisibilityBatch(
        queueUrl: String,
        requests: Collection<SqsChangeVisibilityRequest>,
    ): SqsBatchVisibilityResult {
        val batch = requests.toList()
        requireBatchSize(batch.size, "batch visibility supports at most 10 messages")
        require(batch.map { it.messageId }.distinct().size == batch.size &&
            batch.map { it.receiptHandle }.distinct().size == batch.size) {
            "duplicate batch visibility request"
        }
        batch.forEach { requireVisibilityTimeout(it.timeoutSeconds) }
        if (batch.isEmpty()) {
            return SqsBatchVisibilityResult(emptyList(), emptyList())
        }

        val entries = batch.mapIndexed { index, request ->
            ChangeMessageVisibilityBatchRequestEntry.builder()
                .id("entry-$index")
                .receiptHandle(request.receiptHandle)
                .visibilityTimeout(request.timeoutSeconds)
                .build()
        }
        val response = sqsAsyncClient.changeMessageVisibilityBatch {
            it.queueUrl(queueUrl)
            it.entries(entries)
        }.await()
        val byEntryId = batch.mapIndexed { index, request -> "entry-$index" to request.messageId }.toMap()
        val successfulEntries = response.successful().orEmpty().map { it.id() }
        val failedResponses = response.failed().orEmpty()
        val responseIds = successfulEntries + response.failed().orEmpty().map { it.id() }
        validateBatchEntryIds(byEntryId.keys.toList(), responseIds) { expected, actual ->
            SqsBatchVisibilityProtocolException(expected, actual)
        }
        val order = byEntryId.keys.withIndex().associate { it.value to it.index }
        return SqsBatchVisibilityResult(
            successfulMessageIds = successfulEntries.sortedBy { order.getValue(it) }
                .map { byEntryId.getValue(it) },
            failed = failedResponses.sortedBy { order.getValue(it.id()) }.map { responseEntry ->
                SqsBatchAcknowledgementFailure(
                    messageId = byEntryId.getValue(responseEntry.id()),
                    code = responseEntry.code(),
                    detail = responseEntry.message(),
                    senderFault = responseEntry.senderFault(),
                )
            },
        )
    }

    override fun receiveFlow(
        queueUrl: String,
        maxMessages: Int,
        waitTimeSeconds: Int,
        visibilityTimeoutSeconds: Int?,
    ): Flow<SqsReceivedMessage> = flow {
        while (true) {
            currentCoroutineContext().ensureActive()
            receive(queueUrl, maxMessages, waitTimeSeconds, visibilityTimeoutSeconds).forEach {
                emit(it)
            }
        }
    }

    private fun SqsProperties.RedrivePolicy.toRedrivePolicyJson(): String =
        """{"deadLetterTargetArn":"${deadLetterTargetArn.escapeJson()}","maxReceiveCount":"$maxReceiveCount"}"""

    private fun String.escapeJson(): String =
        buildString(length) {
            this@escapeJson.forEach {
                when (it) {
                    '\\' -> append("\\\\")
                    '"'  -> append("\\\"")
                    else -> append(it)
                }
            }
        }
}

private inline fun validateBatchEntryIds(
    expected: List<String>,
    actual: List<String>,
    exception: (List<String>, List<String>) -> RuntimeException,
) {
    if (actual.size != expected.size || actual.distinct().size != expected.size || actual.toSet() != expected.toSet()) {
        throw exception(expected, actual)
    }
}
