package io.bluetape4k.aws.spring.sqs

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityResponse
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import software.amazon.awssdk.services.sqs.model.SendMessageResponse

object NoopSqsOperations: SqsOperations {
    override suspend fun getQueueUrl(queueName: String): String =
        "https://sqs.ap-northeast-2.amazonaws.com/000000000000/$queueName"

    override suspend fun createQueue(
        queueName: String,
        attributes: Map<QueueAttributeName, String>,
    ): String =
        getQueueUrl(queueName)

    override suspend fun createConfiguredQueue(queueName: String): String =
        getQueueUrl(queueName)

    override suspend fun send(queueUrl: String, body: String, delaySeconds: Int?): SendMessageResponse =
        SendMessageResponse.builder().messageId("noop").build()

    override suspend fun receive(
        queueUrl: String,
        maxMessages: Int,
        waitTimeSeconds: Int,
        visibilityTimeoutSeconds: Int?,
    ): List<SqsReceivedMessage> =
        emptyList()

    override suspend fun delete(queueUrl: String, receiptHandle: String): DeleteMessageResponse =
        DeleteMessageResponse.builder().build()

    override suspend fun changeVisibility(
        queueUrl: String,
        receiptHandle: String,
        timeoutSeconds: Int,
    ): ChangeMessageVisibilityResponse =
        ChangeMessageVisibilityResponse.builder().build()

    override fun receiveFlow(
        queueUrl: String,
        maxMessages: Int,
        waitTimeSeconds: Int,
        visibilityTimeoutSeconds: Int?,
    ): Flow<SqsReceivedMessage> =
        emptyFlow()
}
