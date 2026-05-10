@file:Suppress("DEPRECATION")

package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.testcontainers.aws.LocalStackServer
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.ProxyFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SqsListenerLocalStackTest {

    companion object {
        private val localStack: LocalStackServer = LocalStackServer().withServices("sqs")

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            localStack.start()
        }

        @JvmStatic
        @AfterAll
        fun afterAll() {
            localStack.stop()
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
                )
            )
            .withUserConfiguration(userConfiguration)
            .withBean(AwsCredentialsProvider::class.java, { localStack.getCredentialProvider() })
            .withPropertyValues(
                "bluetape4k.aws.sqs.region=${localStack.regionName}",
                "bluetape4k.aws.sqs.endpoint-override=${localStack.awsEndpoint}",
                "bluetape4k.aws.sqs.listener.max-messages=1",
                "bluetape4k.aws.sqs.listener.wait-time-seconds=1",
                "bluetape4k.aws.sqs.listener.stop-timeout-millis=5000",
                "test.queue-url=$queueUrl",
                *properties,
            )

    private fun createQueue(operations: SqsOperations, prefix: String): String =
        runBlocking { operations.createQueue("$prefix-${UUID.randomUUID()}") }

    @Test
    fun `listener deletes message after successful handling`() {
        val bootstrap = contextRunner(queueUrl = "bootstrap", NoListenerConfig::class.java)
            .withPropertyValues("bluetape4k.aws.sqs.listener.enabled=false")

        bootstrap.run { bootstrapContext ->
            val queueUrl = createQueue(bootstrapContext.getBean(SqsOperations::class.java), "listener-success")

            contextRunner(queueUrl, StringListenerConfig::class.java).run { context ->
                val operations = context.getBean(SqsOperations::class.java)
                val listener = context.getBean(StringListener::class.java)

                runBlocking { operations.send(queueUrl, "listener-ok") }

                await.atMost(Duration.ofSeconds(10)).untilAsserted {
                    assertThat(listener.bodies).contains("listener-ok")
                }
                runBlocking {
                    assertThat(operations.receive(queueUrl, maxMessages = 1, waitTimeSeconds = 1)).isEmpty()
                }
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

                runBlocking { operations.send(queueUrl, "listener-suspend-ok") }

                await.atMost(Duration.ofSeconds(10)).untilAsserted {
                    assertThat(listener.bodies).contains("listener-suspend-ok")
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

                runBlocking { operations.send(queueUrl, "listener-fail") }

                await.atMost(Duration.ofSeconds(10)).untilAsserted {
                    assertThat(listener.attempts.get()).isGreaterThanOrEqualTo(2)
                }
                val stopped = CountDownLatch(1)
                context.getBean(SqsMessageListenerContainerRegistry::class.java).stop {
                    stopped.countDown()
                }
                assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue()
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

                runBlocking {
                    operations.send(queueUrl, "concurrency-1")
                    operations.send(queueUrl, "concurrency-2")
                }

                await.atMost(Duration.ofSeconds(10)).untilAsserted {
                    assertThat(listener.bodies).contains("concurrency-1", "concurrency-2")
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

                runBlocking { operations.send(queueUrl, "listener-proxy-ok") }

                await.atMost(Duration.ofSeconds(10)).untilAsserted {
                    assertThat(ProxiedListener.bodies).contains("listener-proxy-ok")
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
