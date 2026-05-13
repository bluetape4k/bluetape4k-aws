@file:Suppress("DEPRECATION")

package io.bluetape4k.aws.spring.sns

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldEndWith
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeBlank
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.testcontainers.aws.LocalStackServer
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import java.util.UUID

class SnsCoroutinesTemplateLocalStackTest {

    companion object {
        private val localStack: LocalStackServer by lazy {
            LocalStackServer.Launcher.getLocalStack("sns", "sqs")
        }
    }

    private fun contextRunner(): ApplicationContextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                SnsAutoConfiguration::class.java,
            )
        )
        .withBean(AwsCredentialsProvider::class.java, { localStack.getCredentialProvider() })
        .withPropertyValues(
            "bluetape4k.aws.sns.region=${localStack.regionName}",
            "bluetape4k.aws.sns.endpoint-override=${localStack.awsEndpoint}",
        )

    @Test
    fun `create find and publish standard topic through SnsOperations`() {
        contextRunner().run { context ->
            val operations = context.getBean(SnsOperations::class.java)

            runSuspendIO {
                val topicName = "standard-${UUID.randomUUID()}"
                val topicArn = operations.createTopic(topicName)

                topicArn shouldEndWith ":$topicName"
                operations.findTopicArn(topicName) shouldBeEqualTo topicArn

                val published = operations.publish(
                    SnsPublishRequest(
                        topicArn = topicArn,
                        subject = "standard",
                        message = "hello sns",
                    )
                )
                published.messageId().shouldNotBeBlank()
            }
        }
    }

    @Test
    fun `create configured topic from properties`() {
        contextRunner()
            .withPropertyValues("bluetape4k.aws.sns.topics.configured.attributes.Environment=test")
            .run { context ->
                val operations = context.getBean(SnsOperations::class.java)

                runSuspendIO {
                    val topicArn = operations.createConfiguredTopic("configured")

                    topicArn shouldEndWith ":configured"
                    operations.findTopicArn("configured") shouldBeEqualTo topicArn
                }
            }
    }

    @Test
    fun `create FIFO topic and publish with message group`() {
        contextRunner().run { context ->
            val operations = context.getBean(SnsOperations::class.java)

            runSuspendIO {
                val topicName = "fifo-${UUID.randomUUID()}.fifo"
                val topicArn = operations.createFifoTopic(
                    topicName = topicName,
                    contentBasedDeduplication = false,
                    fifoThroughputScope = SnsFifoThroughputScope.MESSAGE_GROUP,
                )

                val published = operations.publish(
                    SnsPublishRequest(
                        topicArn = topicArn,
                        message = "hello fifo sns",
                        messageGroupId = "orders",
                        messageDeduplicationId = UUID.randomUUID().toString(),
                    )
                )

                published.messageId().shouldNotBeBlank()
            }
        }
    }

    @Test
    fun `reject FIFO only publish fields for standard topic`() {
        val error = assertFailsWith<IllegalArgumentException> {
            SnsPublishRequest(
                topicArn = "arn:aws:sns:us-east-1:000000000000:standard",
                message = "hello",
                messageGroupId = "orders",
            )
        }
        error.message.orEmpty() shouldContain "not allowed for standard topic"
    }

    @Test
    fun `propagate AWS publish errors`() {
        contextRunner().run { context ->
            val operations = context.getBean(SnsOperations::class.java)

            val error = assertFailsWith<Exception> {
                runSuspendIO {
                    operations.publish(
                        SnsPublishRequest(
                            topicArn = "arn:aws:sns:${localStack.regionName}:000000000000:missing",
                            message = "missing",
                        )
                    )
                }
            }
            error.message.orEmpty() shouldContain "Topic"
        }
    }

    @Test
    fun `publish message to SQS subscription`() {
        contextRunner().run { context ->
            val operations = context.getBean(SnsOperations::class.java)

            runSuspendIO {
                val sqs = sqsAsyncClient()
                try {
                    val topicArn = operations.createTopic("fanout-${UUID.randomUUID()}")
                    val queueUrl = sqs.createQueue {
                        it.queueName("fanout-${UUID.randomUUID()}")
                    }.await().queueUrl()
                    val queueArn = requireNotNull(
                        sqs.getQueueAttributes {
                            it.queueUrl(queueUrl)
                            it.attributeNames(QueueAttributeName.QUEUE_ARN)
                        }.await().attributes()[QueueAttributeName.QUEUE_ARN]
                    ) {
                        "QueueArn attribute must be returned by LocalStack."
                    }

                    val policy = queuePolicy(queueArn = queueArn, topicArn = topicArn)
                    sqs.setQueueAttributes {
                        it.queueUrl(queueUrl)
                        it.attributes(mapOf(QueueAttributeName.POLICY to policy))
                    }.await()

                    context.getBean(software.amazon.awssdk.services.sns.SnsAsyncClient::class.java)
                        .subscribe {
                            it.topicArn(topicArn)
                            it.protocol("sqs")
                            it.endpoint(queueArn)
                            it.returnSubscriptionArn(true)
                        }
                        .await()

                    operations.publish(SnsPublishRequest(topicArn = topicArn, message = "fanout"))

                    val received = sqs.receiveMessage {
                        it.queueUrl(queueUrl)
                        it.maxNumberOfMessages(1)
                        it.waitTimeSeconds(5)
                    }.await().messages()

                    received shouldHaveSize 1
                    received.single().body() shouldContain "fanout"
                } finally {
                    sqs.close()
                }
            }
        }
    }

    private fun sqsAsyncClient(): SqsAsyncClient =
        SqsAsyncClient.builder()
            .credentialsProvider(localStack.getCredentialProvider())
            .region(Region.of(localStack.regionName))
            .endpointOverride(localStack.awsEndpoint)
            .build()

    private fun queuePolicy(queueArn: String, topicArn: String): String =
        """
        {
          "Version":"2012-10-17",
          "Statement":[{
            "Effect":"Allow",
            "Principal":"*",
            "Action":"sqs:SendMessage",
            "Resource":"$queueArn",
            "Condition":{"ArnEquals":{"aws:SourceArn":"$topicArn"}}
          }]
        }
        """.trimIndent()
}
