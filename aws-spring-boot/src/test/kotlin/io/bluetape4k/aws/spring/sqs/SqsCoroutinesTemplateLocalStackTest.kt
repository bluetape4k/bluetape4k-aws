@file:Suppress("DEPRECATION")

package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeBlank
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.testcontainers.aws.LocalStackServer
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import java.util.UUID

class SqsCoroutinesTemplateLocalStackTest {

    companion object {
        private val localStack: LocalStackServer by lazy {
            LocalStackServer.Launcher.getLocalStack("sqs")
        }
    }

    private fun contextRunner(): ApplicationContextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                SqsAutoConfiguration::class.java,
            )
        )
        .withBean(AwsCredentialsProvider::class.java, { localStack.getCredentialProvider() })
        .withPropertyValues(
            "bluetape4k.aws.sqs.region=${localStack.regionName}",
            "bluetape4k.aws.sqs.endpoint-override=${localStack.awsEndpoint}",
            "bluetape4k.aws.sqs.listener.enabled=false",
        )

    @Test
    fun `create send receive and delete message through SqsOperations`() {
        contextRunner().run { context ->
            val operations = context.getBean(SqsOperations::class.java)

            runSuspendIO {
                val queueUrl = operations.createQueue("template-${UUID.randomUUID()}")
                val sent = operations.send(queueUrl, "hello sqs")
                sent.messageId().shouldNotBeBlank()

                val received = operations.receive(queueUrl, maxMessages = 1, waitTimeSeconds = 1)
                received shouldHaveSize 1
                received.single().body shouldBeEqualTo "hello sqs"

                operations.delete(queueUrl, received.single().receiptHandle)
                operations.receive(queueUrl, maxMessages = 1, waitTimeSeconds = 1).shouldBeEmpty()
            }
        }
    }

    @Test
    fun `receiveFlow emits messages without deleting automatically`() {
        contextRunner().run { context ->
            val operations = context.getBean(SqsOperations::class.java)

            runSuspendIO {
                val queueUrl = operations.createQueue("flow-${UUID.randomUUID()}")
                operations.send(queueUrl, "flow sqs")

                val received = operations.receiveFlow(queueUrl, maxMessages = 1, waitTimeSeconds = 1).first()
                received.body shouldBeEqualTo "flow sqs"

                operations.changeVisibility(queueUrl, received.receiptHandle, 0)
                val receivedAgain = operations.receive(queueUrl, maxMessages = 1, waitTimeSeconds = 1)
                receivedAgain.map { it.body } shouldContain "flow sqs"
                operations.delete(queueUrl, receivedAgain.single().receiptHandle)
            }
        }
    }

    @Test
    fun `FIFO message exposes group deduplication and message attributes`() {
        contextRunner().run { context ->
            val operations = context.getBean(SqsOperations::class.java)

            runSuspendIO {
                val queueUrl = operations.createQueue(
                    queueName = "fifo-${UUID.randomUUID()}.fifo",
                    attributes = mapOf(
                        QueueAttributeName.FIFO_QUEUE to "true",
                    )
                )
                operations.send(
                    SqsSendRequest(
                        queueUrl = queueUrl,
                        body = "hello fifo sqs",
                        messageGroupId = "orders",
                        messageDeduplicationId = "order-1",
                        messageAttributes = mapOf(
                            "source" to MessageAttributeValue.builder()
                                .dataType("String")
                                .stringValue("spring")
                                .build()
                        )
                    )
                )

                val received = operations.receive(queueUrl, maxMessages = 1, waitTimeSeconds = 1)
                received shouldHaveSize 1

                val message = received.single()
                message.body shouldBeEqualTo "hello fifo sqs"
                message.messageGroupId shouldBeEqualTo "orders"
                message.messageDeduplicationId shouldBeEqualTo "order-1"
                message.sequenceNumber.shouldNotBeNull().shouldNotBeBlank()
                message.approximateReceiveCount shouldBeEqualTo 1
                message.messageAttributes["source"]?.stringValue() shouldBeEqualTo "spring"

                operations.delete(queueUrl, message.receiptHandle)
            }
        }
    }
}
