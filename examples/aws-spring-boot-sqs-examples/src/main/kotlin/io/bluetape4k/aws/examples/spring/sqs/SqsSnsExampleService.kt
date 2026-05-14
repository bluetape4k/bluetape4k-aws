package io.bluetape4k.aws.examples.spring.sqs

import io.bluetape4k.aws.spring.sns.SnsOperations
import io.bluetape4k.aws.spring.sns.SnsPublishRequest
import io.bluetape4k.aws.spring.sqs.SqsOperations
import kotlinx.coroutines.future.await
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.QueueAttributeName

@Service
class SqsSnsExampleService(
    private val sqs: SqsOperations,
    private val sns: SnsOperations,
    private val sqsAsyncClient: SqsAsyncClient,
    private val snsAsyncClient: SnsAsyncClient,
) {

    suspend fun createQueue(queueName: String): QueueResponse {
        val queueUrl = sqs.createQueue(queueName)
        return QueueResponse(queueName = queueName, queueUrl = queueUrl, queueArn = queueArn(queueUrl))
    }

    suspend fun send(queueNameOrUrl: String, request: SendQueueMessageRequest): QueueSendResponse {
        val queueUrl = resolveQueueUrl(queueNameOrUrl)
        val response = sqs.send(queueUrl = queueUrl, body = request.message, delaySeconds = request.delaySeconds)
        return QueueSendResponse(queueUrl = queueUrl, messageId = response.messageId())
    }

    suspend fun receive(queueNameOrUrl: String, deleteAfterReceive: Boolean = false): List<QueueMessageResponse> {
        val queueUrl = resolveQueueUrl(queueNameOrUrl)
        val messages = sqs.receive(queueUrl = queueUrl, maxMessages = 10, waitTimeSeconds = 1)

        if (deleteAfterReceive) {
            messages.forEach { sqs.delete(queueUrl, it.receiptHandle) }
        }

        return messages.map {
            QueueMessageResponse(
                queueUrl = queueUrl,
                messageId = it.message.messageId(),
                body = it.body,
                receiptHandle = it.receiptHandle,
            )
        }
    }

    suspend fun createFanout(request: FanoutSetupRequest): FanoutSetupResponse {
        val topicArn = sns.createTopic(request.topicName)
        val queueUrl = sqs.createQueue(request.queueName)
        val queueArn = queueArn(queueUrl)

        sqsAsyncClient.setQueueAttributes {
            it.queueUrl(queueUrl)
            it.attributes(mapOf(QueueAttributeName.POLICY to queuePolicy(queueArn, topicArn)))
        }.await()

        val subscriptionArn = snsAsyncClient.subscribe {
            it.topicArn(topicArn)
            it.protocol("sqs")
            it.endpoint(queueArn)
            it.returnSubscriptionArn(true)
        }.await().subscriptionArn()

        return FanoutSetupResponse(
            topicArn = topicArn,
            queueUrl = queueUrl,
            queueArn = queueArn,
            subscriptionArn = subscriptionArn,
        )
    }

    suspend fun publish(request: PublishTopicMessageRequest): TopicPublishResponse {
        val response = sns.publish(
            SnsPublishRequest(
                topicArn = request.topicArn,
                subject = request.subject,
                message = request.message,
            )
        )
        return TopicPublishResponse(topicArn = request.topicArn, messageId = response.messageId())
    }

    suspend fun createDlqPair(request: DlqSetupRequest): DlqSetupResponse {
        val dlqUrl = sqs.createQueue(request.dlqName)
        val dlqArn = queueArn(dlqUrl)
        val sourceQueueUrl = sqs.createQueue(
            queueName = request.queueName,
            attributes = mapOf(
                QueueAttributeName.REDRIVE_POLICY to
                    """{"deadLetterTargetArn":"$dlqArn","maxReceiveCount":"${request.maxReceiveCount}"}""",
            ),
        )

        return DlqSetupResponse(
            queueUrl = sourceQueueUrl,
            dlqUrl = dlqUrl,
            dlqArn = dlqArn,
            maxReceiveCount = request.maxReceiveCount,
        )
    }

    private suspend fun resolveQueueUrl(queueNameOrUrl: String): String =
        if (queueNameOrUrl.startsWith("http://") || queueNameOrUrl.startsWith("https://")) {
            queueNameOrUrl
        } else {
            sqs.getQueueUrl(queueNameOrUrl)
        }

    private suspend fun queueArn(queueUrl: String): String =
        requireNotNull(
            sqsAsyncClient.getQueueAttributes {
                it.queueUrl(queueUrl)
                it.attributeNames(QueueAttributeName.QUEUE_ARN)
            }.await().attributes()[QueueAttributeName.QUEUE_ARN]
        ) {
            "QueueArn attribute must be returned by SQS for queueUrl=$queueUrl."
        }

    private fun queuePolicy(queueArn: String, topicArn: String): String =
        """
        {
          "Version":"2012-10-17",
          "Statement":[{
            "Effect":"Allow",
            "Principal":{"Service":"sns.amazonaws.com"},
            "Action":"sqs:SendMessage",
            "Resource":"$queueArn",
            "Condition":{"ArnEquals":{"aws:SourceArn":"$topicArn"}}
          }]
        }
        """.trimIndent()
}

data class QueueResponse(
    val queueName: String,
    val queueUrl: String,
    val queueArn: String,
)

data class SendQueueMessageRequest(
    val message: String,
    val delaySeconds: Int? = null,
) {
    init {
        require(message.isNotBlank()) { "message must not be blank." }
    }
}

data class QueueSendResponse(
    val queueUrl: String,
    val messageId: String?,
)

data class QueueMessageResponse(
    val queueUrl: String,
    val messageId: String?,
    val body: String,
    val receiptHandle: String,
)

data class FanoutSetupRequest(
    val topicName: String,
    val queueName: String,
) {
    init {
        require(topicName.isNotBlank()) { "topicName must not be blank." }
        require(queueName.isNotBlank()) { "queueName must not be blank." }
    }
}

data class FanoutSetupResponse(
    val topicArn: String,
    val queueUrl: String,
    val queueArn: String,
    val subscriptionArn: String?,
)

data class PublishTopicMessageRequest(
    val topicArn: String,
    val message: String,
    val subject: String? = null,
) {
    init {
        require(topicArn.isNotBlank()) { "topicArn must not be blank." }
        require(message.isNotBlank()) { "message must not be blank." }
        subject?.let { require(it.isNotBlank()) { "subject must not be blank." } }
    }
}

data class TopicPublishResponse(
    val topicArn: String,
    val messageId: String?,
)

data class DlqSetupRequest(
    val queueName: String,
    val dlqName: String,
    val maxReceiveCount: Int = 3,
) {
    init {
        require(queueName.isNotBlank()) { "queueName must not be blank." }
        require(dlqName.isNotBlank()) { "dlqName must not be blank." }
        require(maxReceiveCount > 0) { "maxReceiveCount must be greater than zero." }
    }
}

data class DlqSetupResponse(
    val queueUrl: String,
    val dlqUrl: String,
    val dlqArn: String,
    val maxReceiveCount: Int,
)
