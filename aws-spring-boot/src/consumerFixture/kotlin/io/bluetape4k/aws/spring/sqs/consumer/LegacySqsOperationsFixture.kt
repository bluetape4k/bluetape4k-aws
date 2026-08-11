package io.bluetape4k.aws.spring.sqs.consumer

import io.bluetape4k.aws.spring.sqs.SqsOperations
import io.bluetape4k.aws.spring.sqs.SqsReceivedMessage
import io.bluetape4k.aws.spring.sqs.SqsSendRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityResponse
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import software.amazon.awssdk.services.sqs.model.SendMessageResponse

/**
 * 새 batch default method를 알지 못하는 기존 consumer 구현체의 source 모양입니다.
 */
class LegacySqsOperationsFixture: SqsOperations {
    override suspend fun getQueueUrl(queueName: String): String = queueName

    override suspend fun createQueue(
        queueName: String,
        attributes: Map<QueueAttributeName, String>,
    ): String = queueName

    override suspend fun createConfiguredQueue(queueName: String): String = queueName

    override suspend fun send(queueUrl: String, body: String, delaySeconds: Int?): SendMessageResponse =
        SendMessageResponse.builder().build()

    override suspend fun send(request: SqsSendRequest): SendMessageResponse =
        SendMessageResponse.builder().build()

    override suspend fun receive(
        queueUrl: String,
        maxMessages: Int,
        waitTimeSeconds: Int,
        visibilityTimeoutSeconds: Int?,
    ): List<SqsReceivedMessage> = emptyList()

    override suspend fun delete(queueUrl: String, receiptHandle: String): DeleteMessageResponse =
        DeleteMessageResponse.builder().build()

    override suspend fun changeVisibility(
        queueUrl: String,
        receiptHandle: String,
        timeoutSeconds: Int,
    ): ChangeMessageVisibilityResponse = ChangeMessageVisibilityResponse.builder().build()

    override fun receiveFlow(
        queueUrl: String,
        maxMessages: Int,
        waitTimeSeconds: Int,
        visibilityTimeoutSeconds: Int?,
    ): Flow<SqsReceivedMessage> = emptyFlow()
}
