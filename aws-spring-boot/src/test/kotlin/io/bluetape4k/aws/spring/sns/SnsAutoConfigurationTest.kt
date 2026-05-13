package io.bluetape4k.aws.spring.sns

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.services.sns.SnsAsyncClient

class SnsAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                SnsAutoConfiguration::class.java,
            )
        )
        .withPropertyValues("bluetape4k.aws.sns.region=us-east-1")

    @Test
    fun `register SNS client and operations`() {
        contextRunner.run { context ->
            context.getBeansOfType(SnsAsyncClient::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(SnsProperties::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(SnsOperations::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(SnsCoroutinesTemplate::class.java).size shouldBeEqualTo 1
        }
    }

    @Test
    fun `back off when SNS auto configuration disabled`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sns.enabled=false")
            .run { context ->
                context.getBeansOfType(SnsAsyncClient::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(SnsOperations::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `custom client bean backs off auto configured client`() {
        val customClient = mockk<SnsAsyncClient>(relaxed = true)

        contextRunner
            .withBean(SnsAsyncClient::class.java, { customClient })
            .run { context ->
                context.getBeansOfType(SnsAsyncClient::class.java).size shouldBeEqualTo 1
                context.getBean(SnsAsyncClient::class.java) shouldBeSameInstanceAs customClient
                context.getBeansOfType(SnsOperations::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `custom operations bean backs off template`() {
        contextRunner
            .withBean(SnsOperations::class.java, { NoopSnsOperations })
            .run { context ->
                context.getBeansOfType(SnsOperations::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(SnsCoroutinesTemplate::class.java).size shouldBeEqualTo 0
                context.getBean(SnsOperations::class.java) shouldBeSameInstanceAs NoopSnsOperations
            }
    }

    @Test
    fun `endpoint override requires region`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SnsAutoConfiguration::class.java))
            .withPropertyValues("bluetape4k.aws.sns.endpoint-override=http://localhost:4566")
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                messages shouldContain "region is required"
            }
    }

    @Test
    fun `endpoint override binds when region is present`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sns.endpoint-override=http://localhost:4566")
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBean(SnsProperties::class.java).endpointOverride.toString() shouldBeEqualTo
                    "http://localhost:4566"
            }
    }

    @Test
    fun `SNS auto configuration backs off when SNS SDK is absent`() {
        contextRunner
            .withClassLoader(FilteredClassLoader("software.amazon.awssdk.services.sns"))
            .run { context ->
                context.getBeansOfType(SnsAsyncClient::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(SnsOperations::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `FIFO throughput scope property binds`() {
        contextRunner
            .withPropertyValues(
                "bluetape4k.aws.sns.topics[orders.fifo].fifo=true",
                "bluetape4k.aws.sns.topics[orders.fifo].fifo-throughput-scope=message-group",
            )
            .run { context ->
                val properties = context.getBean(SnsProperties::class.java)

                properties.topics["orders.fifo"]?.fifoThroughputScope shouldBeEqualTo
                    SnsFifoThroughputScope.MESSAGE_GROUP
            }
    }

    @Test
    fun `configured FIFO topic name is validated by template`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sns.topics.orders.fifo=true")
            .run { context ->
                val operations = context.getBean(SnsOperations::class.java)

            val error = assertFailsWith<IllegalArgumentException> {
                runSuspendIO {
                    operations.createConfiguredTopic("orders")
                }
                }
                error.message.orEmpty() shouldContain "FIFO topic name"
            }
    }

    @Test
    fun `missing configured topic fails fast`() {
        contextRunner.run { context ->
            val operations = context.getBean(SnsOperations::class.java)

            val error = assertFailsWith<IllegalArgumentException> {
                runSuspendIO {
                    operations.createConfiguredTopic("missing")
                }
            }
            error.message.orEmpty() shouldContain "not configured"
        }
    }
}
