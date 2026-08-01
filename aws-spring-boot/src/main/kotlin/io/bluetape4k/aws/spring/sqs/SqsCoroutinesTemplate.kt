package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.aws.sqs.getQueueUrl
import io.bluetape4k.aws.sqs.send
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.future.await
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityResponse
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import software.amazon.awssdk.services.sqs.model.SendMessageResponse

/**
 * AWS SDK v2 [SqsAsyncClient]를 Coroutines 친화적인 [SqsOperations]로 감싸는 템플릿.
 */
class SqsCoroutinesTemplate(
    private val sqsAsyncClient: SqsAsyncClient,
    private val properties: SqsProperties,
): SqsOperations {

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
