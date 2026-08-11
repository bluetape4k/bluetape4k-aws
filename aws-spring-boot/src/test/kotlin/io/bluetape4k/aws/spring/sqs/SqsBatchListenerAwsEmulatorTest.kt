package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.test.AwsSpringBootTestEmulator
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.junit5.awaitility.untilSuspending
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import org.awaitility.kotlin.await
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Floci-first proof for the optimized SQS batch listener path. */
class SqsBatchListenerAwsEmulatorTest {

    companion object {
        private val awsEmulator by lazy { AwsSpringBootTestEmulator.get("sqs") }
    }

    @Test
    fun `batch listener handles ten messages and deletes them`() {
        val bootstrap = contextRunner("bootstrap", NoListenerConfig::class.java)
            .withPropertyValues("bluetape4k.aws.sqs.listener.enabled=false")

        bootstrap.run { bootstrapContext ->
            val operations = bootstrapContext.getBean(SqsOperations::class.java)
            lateinit var queueUrl: String
            runSuspendIO { queueUrl = operations.createQueue("batch-${UUID.randomUUID()}") }

            contextRunner(queueUrl, BatchListenerConfig::class.java).run { context ->
                val listener = context.getBean(BatchListener::class.java)
                runSuspendIO {
                    repeat(10) { index -> operations.send(queueUrl, "batch-$index") }
                }

                await.atMost(Duration.ofSeconds(20)).untilAsserted {
                    listener.messageIds.size shouldBeGreaterOrEqualTo 10
                }
                listener.invocations.get() shouldBeGreaterOrEqualTo 1
                listener.batchSizes.all { it in 1..10 } shouldBeEqualTo true
                listener.messageIds.distinct().size shouldBeEqualTo 10
                runSuspendIO {
                    await.atMost(Duration.ofSeconds(10)).untilSuspending {
                        operations.receive(queueUrl, maxMessages = 10, waitTimeSeconds = 1).isEmpty()
                    }
                }
            }
        }
    }

    private fun contextRunner(
        queueUrl: String,
        userConfiguration: Class<*>,
        vararg properties: String,
    ): ApplicationContextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AwsAutoConfiguration::class.java,
                    SqsAutoConfiguration::class.java,
                    SqsJacksonMessageConverterAutoConfiguration::class.java,
                )
            )
            .withUserConfiguration(userConfiguration)
            .withBean(AwsCredentialsProvider::class.java, { awsEmulator.getCredentialProvider() })
            .withPropertyValues(
                "bluetape4k.aws.sqs.region=${awsEmulator.regionName}",
                "bluetape4k.aws.sqs.endpoint-override=${awsEmulator.awsEndpoint}",
                "bluetape4k.aws.sqs.listener.max-messages=10",
                "bluetape4k.aws.sqs.listener.wait-time-seconds=1",
                "bluetape4k.aws.sqs.listener.stop-timeout-millis=5000",
                "test.queue-url=$queueUrl",
                *properties,
            )

    @Configuration(proxyBeanMethods = false)
    internal class NoListenerConfig

    @Configuration(proxyBeanMethods = false)
    internal class BatchListenerConfig {
        @Bean
        fun listener(): BatchListener = BatchListener()
    }

    internal class BatchListener {
        val invocations = AtomicInteger()
        val messageIds: MutableList<String> = CopyOnWriteArrayList()
        val batchSizes: MutableList<Int> = CopyOnWriteArrayList()

        @SqsListener(queue = "\${test.queue-url}", batch = true, maxMessages = 10)
        fun handle(messages: List<SqsReceivedMessage>) {
            invocations.incrementAndGet()
            batchSizes += messages.size
            messageIds += messages.map { it.messageId }
        }
    }
}
