@file:Suppress("DEPRECATION")

package io.bluetape4k.aws.examples.spring.sqs

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.sns.SnsAutoConfiguration
import io.bluetape4k.aws.spring.sqs.SqsAutoConfiguration
import io.bluetape4k.aws.spring.sqs.SqsJacksonMessageConverterAutoConfiguration
import io.bluetape4k.aws.spring.sqs.SqsOperations
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.testcontainers.aws.AwsEmulatorServer
import io.bluetape4k.testcontainers.aws.FlociServer
import io.bluetape4k.testcontainers.aws.LocalStackServer
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider

class SqsSnsExampleLocalStackTest {

    companion object {
        private val localStack: LocalStackServer by lazy {
            LocalStackServer.Launcher.getLocalStack("sns", "sqs")
        }

        private val awsEmulator: AwsEmulatorServer by lazy {
            when (val emulator = System.getProperty("bluetape4k.aws.emulator", "floci").trim().lowercase()) {
                "floci" -> FlociServer.Launcher.floci
                "localstack" -> localStack
                else -> error("Unsupported AWS emulator: $emulator. Use floci or localstack.")
            }
        }
    }

    private fun contextRunner(vararg properties: String): ApplicationContextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AwsAutoConfiguration::class.java,
                    SqsAutoConfiguration::class.java,
                    SqsJacksonMessageConverterAutoConfiguration::class.java,
                    SnsAutoConfiguration::class.java,
                    JacksonAutoConfiguration::class.java,
                )
            )
            .withBean(AwsCredentialsProvider::class.java, { awsEmulator.getCredentialProvider() })
            .withPropertyValues(
                "bluetape4k.aws.sqs.region=${awsEmulator.regionName}",
                "bluetape4k.aws.sqs.endpoint-override=${awsEmulator.awsEndpoint}",
                "bluetape4k.aws.sqs.listener.max-messages=1",
                "bluetape4k.aws.sqs.listener.wait-time-seconds=1",
                "bluetape4k.aws.sqs.listener.stop-timeout-millis=5000",
                "bluetape4k.aws.sqs.listener.retry.max-attempts=2",
                "bluetape4k.aws.sqs.listener.retry.initial-backoff=PT0S",
                "bluetape4k.aws.sns.region=${awsEmulator.regionName}",
                "bluetape4k.aws.sns.endpoint-override=${awsEmulator.awsEndpoint}",
                *properties,
            )

    @Test
    fun `example covers REST send listener fanout and DLQ setup`() {
        contextRunner("bluetape4k.aws.sqs.listener.enabled=false").run { bootstrap ->
            val listenerQueueUrl = createQueue(bootstrap.getBean(SqsOperations::class.java), "example-listener")
            val typedListenerQueueUrl = createQueue(bootstrap.getBean(SqsOperations::class.java), "example-typed")
            val retryListenerQueueUrl = createQueue(bootstrap.getBean(SqsOperations::class.java), "example-retry")

            contextRunner(
                "example.aws.sqs.listener-queue=$listenerQueueUrl",
                "example.aws.sqs.typed-listener-queue=$typedListenerQueueUrl",
                "example.aws.sqs.retry-listener-queue=$retryListenerQueueUrl",
            )
                .withUserConfiguration(SpringBootSqsExampleApplication::class.java)
                .run { context ->
                    val controller = context.getBean(SqsSnsExampleController::class.java)
                    val service = context.getBean(SqsSnsExampleService::class.java)
                    val receivedOrderStore = context.getBean(ReceivedOrderStore::class.java)

                    runSuspendIO {
                        val queueName = "example-rest-${Base58.randomString(8)}"
                        val queue = controller.createQueue(queueName)
                        queue.queueArn shouldContain queueName

                        val restMessage = "rest-message-${Base58.randomString(8)}"
                        val sent = controller.send(queue.queueUrl, SendQueueMessageRequest(restMessage))
                        sent.messageId.shouldNotBeEmpty()
                        controller.receive(queue.queueUrl, deleteAfterReceive = true).map { it.body } shouldContain restMessage

                        val listenerMessage = "listener-message-${Base58.randomString(8)}"
                        service.send(listenerQueueUrl, SendQueueMessageRequest(listenerMessage))
                        waitUntil("listener receives $listenerMessage") {
                            receivedOrderStore.recent().contains(listenerMessage)
                        }

                        val typedOrderId = "order-${Base58.randomString(8)}"
                        service.send(
                            typedListenerQueueUrl,
                            SendQueueMessageRequest("""{"id":"$typedOrderId","amount":42}"""),
                        )
                        waitUntil("typed listener receives $typedOrderId") {
                            controller.typedListenerOrders().any { it.id == typedOrderId && it.amount == 42L }
                        }

                        val retryMessage = "retry-message-${Base58.randomString(8)}"
                        service.send(retryListenerQueueUrl, SendQueueMessageRequest(retryMessage))
                        waitUntil("retry listener acknowledges $retryMessage") {
                            receivedOrderStore.recent().contains("retried:$retryMessage") &&
                                receivedOrderStore.attemptCount(retryMessage) == 2
                        }
                        controller.listenerEvents().map { it.listenerId } shouldContain "typed-order-listener"
                        controller.listenerEvents().map { it.listenerId } shouldContain "retrying-order-listener"
                        controller.listenerEvents().map { it.action } shouldContain "ack"

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
                        published.messageId.shouldNotBeEmpty()
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
                        dlq.dlqArn shouldContain "example-dlq"
                        dlq.maxReceiveCount shouldBeEqualTo 2
                    }
                }
        }
    }

    private fun createQueue(operations: SqsOperations, prefix: String): String {
        lateinit var queueUrl: String
        runSuspendIO {
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
