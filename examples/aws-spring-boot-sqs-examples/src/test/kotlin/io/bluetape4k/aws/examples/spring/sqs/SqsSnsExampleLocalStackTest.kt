@file:Suppress("DEPRECATION")

package io.bluetape4k.aws.examples.spring.sqs

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.sns.SnsAutoConfiguration
import io.bluetape4k.aws.spring.sqs.SqsAutoConfiguration
import io.bluetape4k.aws.spring.sqs.SqsOperations
import io.bluetape4k.codec.Base58
import io.bluetape4k.testcontainers.aws.LocalStackServer
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider

class SqsSnsExampleLocalStackTest {

    companion object {
        private val localStack: LocalStackServer by lazy {
            LocalStackServer.Launcher.getLocalStack("sns", "sqs")
        }
    }

    private fun contextRunner(vararg properties: String): ApplicationContextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AwsAutoConfiguration::class.java,
                    SqsAutoConfiguration::class.java,
                    SnsAutoConfiguration::class.java,
                )
            )
            .withBean(AwsCredentialsProvider::class.java, { localStack.getCredentialProvider() })
            .withPropertyValues(
                "bluetape4k.aws.sqs.region=${localStack.regionName}",
                "bluetape4k.aws.sqs.endpoint-override=${localStack.awsEndpoint}",
                "bluetape4k.aws.sqs.listener.max-messages=1",
                "bluetape4k.aws.sqs.listener.wait-time-seconds=1",
                "bluetape4k.aws.sqs.listener.stop-timeout-millis=5000",
                "bluetape4k.aws.sns.region=${localStack.regionName}",
                "bluetape4k.aws.sns.endpoint-override=${localStack.awsEndpoint}",
                *properties,
            )

    @Test
    fun `example covers REST send listener fanout and DLQ setup`() {
        contextRunner("bluetape4k.aws.sqs.listener.enabled=false").run { bootstrap ->
            val listenerQueueUrl = createQueue(bootstrap.getBean(SqsOperations::class.java), "example-listener")

            contextRunner("example.aws.sqs.listener-queue=$listenerQueueUrl")
                .withUserConfiguration(SpringBootSqsExampleApplication::class.java)
                .run { context ->
                    val controller = context.getBean(SqsSnsExampleController::class.java)
                    val service = context.getBean(SqsSnsExampleService::class.java)
                    val receivedOrderStore = context.getBean(ReceivedOrderStore::class.java)

                    runTest {
                        val queueName = "example-rest-${Base58.randomString(8)}"
                        val queue = controller.createQueue(queueName)
                        assertThat(queue.queueArn).contains(queueName)

                        val restMessage = "rest-message-${Base58.randomString(8)}"
                        val sent = controller.send(queue.queueUrl, SendQueueMessageRequest(restMessage))
                        assertThat(sent.messageId).isNotBlank()
                        assertThat(controller.receive(queue.queueUrl, deleteAfterReceive = true).map { it.body })
                            .contains(restMessage)

                        val listenerMessage = "listener-message-${Base58.randomString(8)}"
                        service.send(listenerQueueUrl, SendQueueMessageRequest(listenerMessage))
                        waitUntil("listener receives $listenerMessage") {
                            receivedOrderStore.recent().contains(listenerMessage)
                        }

                        val fanout = controller.createFanout(
                            FanoutSetupRequest(
                                topicName = "example-topic-${Base58.randomString(8)}",
                                queueName = "example-fanout-${Base58.randomString(8)}",
                            )
                        )
                        val fanoutMessage = "fanout-message-${Base58.randomString(8)}"
                        val published = controller.publish(
                            PublishTopicMessageRequest(
                                topicArn = fanout.topicArn,
                                subject = "fanout",
                                message = fanoutMessage,
                            )
                        )
                        assertThat(published.messageId).isNotBlank()
                        waitUntil("fanout delivers $fanoutMessage to ${fanout.queueUrl}") {
                            service.receive(fanout.queueUrl, deleteAfterReceive = true)
                                .any { it.body.contains(fanoutMessage) }
                        }

                        val dlq = controller.createDlqPair(
                            DlqSetupRequest(
                                queueName = "example-source-${Base58.randomString(8)}",
                                dlqName = "example-dlq-${Base58.randomString(8)}",
                                maxReceiveCount = 2,
                            )
                        )
                        assertThat(dlq.dlqArn).contains("example-dlq")
                        assertThat(dlq.maxReceiveCount).isEqualTo(2)
                    }
                }
        }
    }

    private fun createQueue(operations: SqsOperations, prefix: String): String {
        lateinit var queueUrl: String
        runTest {
            queueUrl = operations.createQueue("$prefix-${Base58.randomString(8)}")
        }
        return queueUrl
    }

    private suspend fun waitUntil(description: String, predicate: suspend () -> Boolean) {
        repeat(60) {
            if (predicate()) return
            delay(500)
        }
        check(predicate()) { "Condition was not met before timeout: $description." }
    }
}
