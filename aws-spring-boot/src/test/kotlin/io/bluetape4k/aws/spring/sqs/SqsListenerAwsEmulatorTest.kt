package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldContainAll
import io.bluetape4k.junit5.awaitility.untilSuspending
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.aws.spring.test.AwsSpringBootTestEmulator
import io.bluetape4k.codec.Base58
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import io.bluetape4k.assertions.shouldBeEqualTo
import kotlinx.coroutines.delay
import org.awaitility.kotlin.await
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.ProxyFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SqsListenerAwsEmulatorTest {

    companion object {
        private val awsEmulator by lazy {
            AwsSpringBootTestEmulator.get("sqs")
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
                "bluetape4k.aws.sqs.listener.max-messages=1",
                "bluetape4k.aws.sqs.listener.wait-time-seconds=1",
                "bluetape4k.aws.sqs.listener.stop-timeout-millis=5000",
                "test.queue-url=$queueUrl",
                *properties,
            )

    private fun createQueue(operations: SqsOperations, prefix: String): String {
        lateinit var queueUrl: String
        runSuspendIO {
            queueUrl = operations.createQueue("$prefix-${Base58.randomString(8)}")
        }
        return queueUrl
    }

    @Test
    fun `listener deletes message after successful handling`() {
        val bootstrap = contextRunner(queueUrl = "bootstrap", NoListenerConfig::class.java)
            .withPropertyValues("bluetape4k.aws.sqs.listener.enabled=false")

        bootstrap.run { bootstrapContext ->
            val queueUrl = createQueue(bootstrapContext.getBean(SqsOperations::class.java), "listener-success")

            contextRunner(queueUrl, StringListenerConfig::class.java).run { context ->
                val operations = context.getBean(SqsOperations::class.java)
                val listener = context.getBean(StringListener::class.java)

                runSuspendIO { operations.send(queueUrl, "listener-ok") }

                await.atMost(Duration.ofSeconds(10)).untilAsserted {
                    listener.bodies shouldContain "listener-ok"
                }
                runSuspendIO {
                    await.atMost(Duration.ofSeconds(10)).untilSuspending {
                        operations.receive(queueUrl, maxMessages = 1, waitTimeSeconds = 1).isEmpty()
                    }
                }
            }
        }
    }

    @Test
    fun `visibility heartbeat prevents redelivery during a long handler`() {
        val bootstrap = contextRunner(queueUrl = "bootstrap", NoListenerConfig::class.java)
            .withPropertyValues("bluetape4k.aws.sqs.listener.enabled=false")

        bootstrap.run { bootstrapContext ->
            val bootstrapOperations = bootstrapContext.getBean(SqsOperations::class.java)
            lateinit var queueUrl: String
            runSuspendIO {
                queueUrl = bootstrapOperations.createQueue(
                    "listener-heartbeat-${Base58.randomString(8)}",
                    mapOf(QueueAttributeName.VISIBILITY_TIMEOUT to "2"),
                )
            }

            contextRunner(
                queueUrl,
                HeartbeatListenerConfig::class.java,
                "bluetape4k.aws.sqs.listener.message-visibility-heartbeat-interval-seconds=1",
                "bluetape4k.aws.sqs.listener.message-visibility-heartbeat-seconds=3",
            ).run { context ->
                val operations = context.getBean(SqsOperations::class.java)
                val listener = context.getBean(HeartbeatListener::class.java)
                runSuspendIO { operations.send(queueUrl, "listener-heartbeat") }

                await.atMost(Duration.ofSeconds(10)).untilAsserted {
                    listener.attempts.get() shouldBeGreaterOrEqualTo 1
                }
                runSuspendIO {
                    await.atMost(Duration.ofSeconds(12)).untilSuspending {
                        operations.receive(queueUrl, maxMessages = 1, waitTimeSeconds = 1).isEmpty()
                    }
                }
                listener.attempts.get() shouldBeEqualTo 1
            }
        }
    }

    @Test
    fun `suspend listener receives SqsReceivedMessage`() {
        val bootstrap = contextRunner(queueUrl = "bootstrap", NoListenerConfig::class.java)
            .withPropertyValues("bluetape4k.aws.sqs.listener.enabled=false")

        bootstrap.run { bootstrapContext ->
            val queueUrl = createQueue(bootstrapContext.getBean(SqsOperations::class.java), "listener-suspend")

            contextRunner(queueUrl, SuspendListenerConfig::class.java).run { context ->
                val operations = context.getBean(SqsOperations::class.java)
                val listener = context.getBean(SuspendListener::class.java)

                runSuspendIO { operations.send(queueUrl, "listener-suspend-ok") }

                await.atMost(Duration.ofSeconds(10)).untilAsserted {
                    listener.bodies shouldContain "listener-suspend-ok"
                }
            }
        }
    }

    @Test
    fun `failed listener redelivers message when error visibility is zero`() {
        val bootstrap = contextRunner(queueUrl = "bootstrap", NoListenerConfig::class.java)
            .withPropertyValues("bluetape4k.aws.sqs.listener.enabled=false")

        bootstrap.run { bootstrapContext ->
            val queueUrl = createQueue(bootstrapContext.getBean(SqsOperations::class.java), "listener-fail")

            contextRunner(
                queueUrl,
                FailingListenerConfig::class.java,
                "bluetape4k.aws.sqs.listener.error-visibility-timeout-seconds=0",
            ).run { context ->
                val operations = context.getBean(SqsOperations::class.java)
                val listener = context.getBean(FailingListener::class.java)

                runSuspendIO { operations.send(queueUrl, "listener-fail") }

                await.atMost(Duration.ofSeconds(10)).untilAsserted {
                    listener.attempts.get() shouldBeGreaterOrEqualTo 2
                }
                val stopped = CountDownLatch(1)
                context.getBean(SqsMessageListenerContainerRegistry::class.java).stop {
                    stopped.countDown()
                }
                stopped.await(5, TimeUnit.SECONDS).shouldBeTrue()
            }
        }
    }

    @Test
    fun `listener concurrency starts multiple polling coroutines`() {
        val bootstrap = contextRunner(queueUrl = "bootstrap", NoListenerConfig::class.java)
            .withPropertyValues("bluetape4k.aws.sqs.listener.enabled=false")

        bootstrap.run { bootstrapContext ->
            val queueUrl = createQueue(bootstrapContext.getBean(SqsOperations::class.java), "listener-concurrency")

            contextRunner(
                queueUrl,
                ConcurrencyListenerConfig::class.java,
                "bluetape4k.aws.sqs.listener.concurrency=2",
            ).run { context ->
                val operations = context.getBean(SqsOperations::class.java)
                val listener = context.getBean(ConcurrencyListener::class.java)

                runSuspendIO {
                    operations.send(queueUrl, "concurrency-1")
                    operations.send(queueUrl, "concurrency-2")
                }

                await.atMost(Duration.ofSeconds(10)).untilAsserted {
                    listener.bodies shouldContainAll listOf("concurrency-1", "concurrency-2")
                }
            }
        }
    }

    @Test
    fun `proxied listener is discovered from target class`() {
        val bootstrap = contextRunner(queueUrl = "bootstrap", NoListenerConfig::class.java)
            .withPropertyValues("bluetape4k.aws.sqs.listener.enabled=false")

        bootstrap.run { bootstrapContext ->
            val queueUrl = createQueue(bootstrapContext.getBean(SqsOperations::class.java), "listener-proxy")

            contextRunner(queueUrl, ProxiedListenerConfig::class.java).run { context ->
                val operations = context.getBean(SqsOperations::class.java)
                ProxiedListener.bodies.clear()

                runSuspendIO { operations.send(queueUrl, "listener-proxy-ok") }

                await.atMost(Duration.ofSeconds(10)).untilAsserted {
                    ProxiedListener.bodies shouldContain "listener-proxy-ok"
                }
            }
        }
    }

    @Test
    fun `listener converts JSON payload with Jackson converter`() {
        val bootstrap = contextRunner(queueUrl = "bootstrap", NoListenerConfig::class.java)
            .withPropertyValues("bluetape4k.aws.sqs.listener.enabled=false")

        bootstrap.run { bootstrapContext ->
            val queueUrl = createQueue(bootstrapContext.getBean(SqsOperations::class.java), "listener-json")

            contextRunner(queueUrl, JsonListenerConfig::class.java).run { context ->
                val operations = context.getBean(SqsOperations::class.java)
                val listener = context.getBean(JsonListener::class.java)

                runSuspendIO { operations.send(queueUrl, """{"id":"order-1","amount":42}""") }

                await.atMost(Duration.ofSeconds(10)).untilAsserted {
                    listener.payloads.map { it.id } shouldContain "order-1"
                }
            }
        }
    }

    @Test
    fun `manual acknowledgement deletes message only when listener acknowledges`() {
        val bootstrap = contextRunner(queueUrl = "bootstrap", NoListenerConfig::class.java)
            .withPropertyValues("bluetape4k.aws.sqs.listener.enabled=false")

        bootstrap.run { bootstrapContext ->
            val queueUrl = createQueue(bootstrapContext.getBean(SqsOperations::class.java), "listener-manual-ack")

            contextRunner(queueUrl, ManualAckListenerConfig::class.java).run { context ->
                val operations = context.getBean(SqsOperations::class.java)
                val listener = context.getBean(ManualAckListener::class.java)

                runSuspendIO { operations.send(queueUrl, "manual-ack-ok") }

                await.atMost(Duration.ofSeconds(10)).untilAsserted {
                    listener.bodies shouldContain "manual-ack-ok"
                }
                runSuspendIO {
                    await.atMost(Duration.ofSeconds(10)).untilSuspending {
                        operations.receive(queueUrl, maxMessages = 1, waitTimeSeconds = 1).isEmpty()
                    }
                }
            }
        }
    }

    @Test
    fun `listener retries before failing visibility handling`() {
        val bootstrap = contextRunner(queueUrl = "bootstrap", NoListenerConfig::class.java)
            .withPropertyValues("bluetape4k.aws.sqs.listener.enabled=false")

        bootstrap.run { bootstrapContext ->
            val queueUrl = createQueue(bootstrapContext.getBean(SqsOperations::class.java), "listener-retry")

            contextRunner(
                queueUrl,
                RetryListenerConfig::class.java,
                "bluetape4k.aws.sqs.listener.retry.max-attempts=2",
            ).run { context ->
                val operations = context.getBean(SqsOperations::class.java)
                val listener = context.getBean(RetryListener::class.java)

                runSuspendIO { operations.send(queueUrl, "retry-ok") }

                await.atMost(Duration.ofSeconds(10)).untilAsserted {
                    listener.bodies shouldContain "retry-ok"
                    listener.attempts.get() shouldBeGreaterOrEqualTo 2
                }
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    internal class NoListenerConfig

    @Configuration(proxyBeanMethods = false)
    internal class StringListenerConfig {
        @Bean
        fun listener(): StringListener = StringListener()
    }

    internal class StringListener {
        val bodies: MutableList<String> = CopyOnWriteArrayList()

        @SqsListener("\${test.queue-url}")
        fun handle(body: String) {
            bodies += body
        }
    }

    @Configuration(proxyBeanMethods = false)
    internal class HeartbeatListenerConfig {
        @Bean
        fun listener(): HeartbeatListener = HeartbeatListener()
    }

    internal class HeartbeatListener {
        val attempts = AtomicInteger()

        @SqsListener("\${test.queue-url}")
        suspend fun handle(body: String) {
            attempts.incrementAndGet()
            check(body == "listener-heartbeat")
            delay(3_500)
        }
    }

    @Configuration(proxyBeanMethods = false)
    internal class JsonListenerConfig {
        @Bean
        fun objectMapper(): ObjectMapper = ObjectMapper()

        @Bean
        fun listener(): JsonListener = JsonListener()
    }

    internal class OrderPayload : java.io.Serializable {
        var id: String = ""
        var amount: Int = 0

        companion object {
            private const val serialVersionUID: Long = -8185867051860667321L
        }
    }

    internal class JsonListener {
        val payloads: MutableList<OrderPayload> = CopyOnWriteArrayList()

        @SqsListener("\${test.queue-url}")
        fun handle(payload: OrderPayload) {
            payloads += payload
        }
    }

    @Configuration(proxyBeanMethods = false)
    internal class ManualAckListenerConfig {
        @Bean
        fun listener(): ManualAckListener = ManualAckListener()
    }

    internal class ManualAckListener {
        val bodies: MutableList<String> = CopyOnWriteArrayList()

        @SqsListener("\${test.queue-url}")
        suspend fun handle(body: String, acknowledgement: SqsAcknowledgement) {
            bodies += body
            acknowledgement.acknowledge()
        }
    }

    @Configuration(proxyBeanMethods = false)
    internal class RetryListenerConfig {
        @Bean
        fun listener(): RetryListener = RetryListener()
    }

    internal class RetryListener {
        val attempts = AtomicInteger()
        val bodies: MutableList<String> = CopyOnWriteArrayList()

        @SqsListener("\${test.queue-url}")
        fun handle(body: String) {
            if (attempts.incrementAndGet() == 1) {
                error("retry once")
            }
            bodies += body
        }
    }

    @Configuration(proxyBeanMethods = false)
    internal class SuspendListenerConfig {
        @Bean
        fun listener(): SuspendListener = SuspendListener()
    }

    internal class SuspendListener {
        val bodies: MutableList<String> = CopyOnWriteArrayList()

        @SqsListener("\${test.queue-url}")
        suspend fun handle(message: SqsReceivedMessage) {
            bodies += message.body
        }
    }

    @Configuration(proxyBeanMethods = false)
    internal class FailingListenerConfig {
        @Bean
        fun listener(): FailingListener = FailingListener()
    }

    internal class FailingListener {
        val attempts = AtomicInteger()

        @SqsListener("\${test.queue-url}")
        fun handle(body: String) {
            attempts.incrementAndGet()
            error("failed to process $body")
        }
    }

    @Configuration(proxyBeanMethods = false)
    internal class ConcurrencyListenerConfig {
        @Bean
        fun listener(): ConcurrencyListener = ConcurrencyListener()
    }

    internal class ConcurrencyListener {
        private val latch = CountDownLatch(2)
        val bodies: MutableList<String> = CopyOnWriteArrayList()

        @SqsListener("\${test.queue-url}")
        fun handle(body: String) {
            bodies += body
            latch.countDown()
            latch.await()
        }
    }

    @Configuration(proxyBeanMethods = false)
    internal class ProxiedListenerConfig {
        @Bean
        fun listener(): ProxiedListener {
            val proxyFactory = ProxyFactory(ProxiedListener())
            proxyFactory.isProxyTargetClass = true
            return proxyFactory.proxy as ProxiedListener
        }
    }

    open class ProxiedListener {
        companion object {
            val bodies: MutableList<String> = CopyOnWriteArrayList()
        }

        @SqsListener("\${test.queue-url}")
        open fun handle(body: String) {
            bodies += body
        }
    }
}
