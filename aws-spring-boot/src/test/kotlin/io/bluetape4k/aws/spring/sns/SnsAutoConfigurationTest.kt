package io.bluetape4k.aws.spring.sns

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
            assertThat(context).hasSingleBean(SnsAsyncClient::class.java)
            assertThat(context).hasSingleBean(SnsProperties::class.java)
            assertThat(context).hasSingleBean(SnsOperations::class.java)
            assertThat(context).hasSingleBean(SnsCoroutinesTemplate::class.java)
        }
    }

    @Test
    fun `back off when SNS auto configuration disabled`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sns.enabled=false")
            .run { context ->
                assertThat(context).doesNotHaveBean(SnsAsyncClient::class.java)
                assertThat(context).doesNotHaveBean(SnsOperations::class.java)
            }
    }

    @Test
    fun `custom client bean backs off auto configured client`() {
        val customClient = mockk<SnsAsyncClient>(relaxed = true)

        contextRunner
            .withBean(SnsAsyncClient::class.java, { customClient })
            .run { context ->
                assertThat(context).hasSingleBean(SnsAsyncClient::class.java)
                assertThat(context.getBean(SnsAsyncClient::class.java)).isSameAs(customClient)
                assertThat(context).hasSingleBean(SnsOperations::class.java)
            }
    }

    @Test
    fun `custom operations bean backs off template`() {
        contextRunner
            .withBean(SnsOperations::class.java, { NoopSnsOperations })
            .run { context ->
                assertThat(context).hasSingleBean(SnsOperations::class.java)
                assertThat(context).doesNotHaveBean(SnsCoroutinesTemplate::class.java)
                assertThat(context.getBean(SnsOperations::class.java)).isSameAs(NoopSnsOperations)
            }
    }

    @Test
    fun `endpoint override requires region`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SnsAutoConfiguration::class.java))
            .withPropertyValues("bluetape4k.aws.sns.endpoint-override=http://localhost:4566")
            .run { context ->
                assertThat(context).hasFailed()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                assertThat(messages).contains("region is required")
            }
    }

    @Test
    fun `endpoint override binds when region is present`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sns.endpoint-override=http://localhost:4566")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(SnsProperties::class.java).endpointOverride.toString())
                    .isEqualTo("http://localhost:4566")
            }
    }

    @Test
    fun `SNS auto configuration backs off when SNS SDK is absent`() {
        contextRunner
            .withClassLoader(FilteredClassLoader("software.amazon.awssdk.services.sns"))
            .run { context ->
                assertThat(context).doesNotHaveBean(SnsAsyncClient::class.java)
                assertThat(context).doesNotHaveBean(SnsOperations::class.java)
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

                assertThat(properties.topics["orders.fifo"]?.fifoThroughputScope)
                    .isEqualTo(SnsFifoThroughputScope.MESSAGE_GROUP)
            }
    }

    @Test
    fun `configured FIFO topic name is validated by template`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sns.topics.orders.fifo=true")
            .run { context ->
                val operations = context.getBean(SnsOperations::class.java)

                assertThatThrownBy {
                    runBlocking {
                        operations.createConfiguredTopic("orders")
                    }
                }.isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining("FIFO topic name")
            }
    }

    @Test
    fun `missing configured topic fails fast`() {
        contextRunner.run { context ->
            val operations = context.getBean(SnsOperations::class.java)

            assertThatThrownBy {
                runBlocking {
                    operations.createConfiguredTopic("missing")
                }
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("not configured")
        }
    }
}
