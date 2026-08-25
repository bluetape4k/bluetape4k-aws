package io.bluetape4k.aws.examples.spring.sqs

import io.bluetape4k.aws.spring.sns.SnsOperations
import io.bluetape4k.aws.spring.sns.SnsPublishRequest
import io.bluetape4k.aws.spring.sqs.SqsOperations
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireNotBlank
import kotlinx.coroutines.future.await
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import java.io.Serializable

/**
 * Spring Boot AWS facade를 조합해 SQS/SNS 예제 작업을 제공하는 서비스입니다.
 */
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

/** 생성된 SQS queue의 이름과 endpoint를 반환합니다. */
data class QueueResponse(
    val queueName: String,
    val queueUrl: String,
    val queueArn: String,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** SQS queue로 보낼 메시지 요청입니다. */
data class SendQueueMessageRequest(
    val message: String,
    val delaySeconds: Int? = null,
): Serializable {
    init {
        message.requireNotBlank("message")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** SQS 전송 결과입니다. */
data class QueueSendResponse(
    val queueUrl: String,
    val messageId: String?,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** SQS에서 수신한 메시지 하나를 표현합니다. */
data class QueueMessageResponse(
    val queueUrl: String,
    val messageId: String?,
    val body: String,
    val receiptHandle: String,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** SNS topic과 SQS queue를 연결하는 fanout 설정 요청입니다. */
data class FanoutSetupRequest(
    val topicName: String,
    val queueName: String,
): Serializable {
    init {
        topicName.requireNotBlank("topicName")
        queueName.requireNotBlank("queueName")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** SNS/SQS fanout 생성 결과입니다. */
data class FanoutSetupResponse(
    val topicArn: String,
    val queueUrl: String,
    val queueArn: String,
    val subscriptionArn: String?,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** SNS topic으로 보낼 메시지 요청입니다. */
data class PublishTopicMessageRequest(
    val topicArn: String,
    val message: String,
    val subject: String? = null,
): Serializable {
    init {
        topicArn.requireNotBlank("topicArn")
        message.requireNotBlank("message")
        subject?.requireNotBlank("subject")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** SNS publish 결과입니다. */
data class TopicPublishResponse(
    val topicArn: String,
    val messageId: String?,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** 원본 queue와 dead-letter queue를 함께 만드는 요청입니다. */
data class DlqSetupRequest(
    val queueName: String,
    val dlqName: String,
    val maxReceiveCount: Int = 3,
): Serializable {
    init {
        queueName.requireNotBlank("queueName")
        dlqName.requireNotBlank("dlqName")
        maxReceiveCount.requireGt(0, "maxReceiveCount")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** dead-letter queue 설정 결과입니다. */
data class DlqSetupResponse(
    val queueUrl: String,
    val dlqUrl: String,
    val dlqArn: String,
    val maxReceiveCount: Int,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
