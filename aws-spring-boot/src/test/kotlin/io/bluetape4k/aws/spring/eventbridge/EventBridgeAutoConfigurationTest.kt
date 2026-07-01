package io.bluetape4k.aws.spring.eventbridge

import io.bluetape4k.aws.spring.AwsAsyncClientCustomizer
import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.AwsClientCustomizationContext
import io.bluetape4k.aws.spring.AwsClientCustomizer
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import software.amazon.awssdk.awscore.client.builder.AwsAsyncClientBuilder
import software.amazon.awssdk.services.eventbridge.EventBridgeAsyncClient
import software.amazon.awssdk.services.eventbridge.EventBridgeAsyncClientBuilder
import software.amazon.awssdk.services.eventbridge.model.CreateEventBusResponse
import software.amazon.awssdk.services.eventbridge.model.DeleteEventBusResponse
import software.amazon.awssdk.services.eventbridge.model.DeleteRuleResponse
import software.amazon.awssdk.services.eventbridge.model.ListRulesResponse
import software.amazon.awssdk.services.eventbridge.model.ListTargetsByRuleResponse
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse
import software.amazon.awssdk.services.eventbridge.model.PutRuleResponse
import software.amazon.awssdk.services.eventbridge.model.PutTargetsResponse
import software.amazon.awssdk.services.eventbridge.model.RemoveTargetsResponse
import software.amazon.awssdk.services.eventbridge.model.RuleState
import software.amazon.awssdk.services.eventbridge.model.Target

class EventBridgeAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                EventBridgeAutoConfiguration::class.java,
            )
        )
        .withPropertyValues("bluetape4k.aws.eventbridge.region=us-east-1")

    @Test
    fun `register EventBridge client and operations`() {
        contextRunner.run { context ->
            context.getBeansOfType(EventBridgeAsyncClient::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(EventBridgeProperties::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(EventBridgeOperations::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(EventBridgeCoroutinesTemplate::class.java).size shouldBeEqualTo 1
        }
    }

    @Test
    fun `back off when EventBridge auto configuration disabled`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.eventbridge.enabled=false")
            .run { context ->
                context.getBeansOfType(EventBridgeAsyncClient::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(EventBridgeOperations::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `custom client bean backs off auto configured client`() {
        val customClient = mockk<EventBridgeAsyncClient>(relaxed = true)

        contextRunner
            .withBean(EventBridgeAsyncClient::class.java, { customClient })
            .run { context ->
                context.getBeansOfType(EventBridgeAsyncClient::class.java).size shouldBeEqualTo 1
                context.getBean(EventBridgeAsyncClient::class.java) shouldBeSameInstanceAs customClient
                context.getBeansOfType(EventBridgeOperations::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `custom operations bean backs off template`() {
        contextRunner
            .withBean(EventBridgeOperations::class.java, { NoopEventBridgeOperations })
            .run { context ->
                context.getBeansOfType(EventBridgeOperations::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(EventBridgeCoroutinesTemplate::class.java).size shouldBeEqualTo 0
                context.getBean(EventBridgeOperations::class.java) shouldBeSameInstanceAs NoopEventBridgeOperations
            }
    }

    @Test
    fun `endpoint override requires region`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EventBridgeAutoConfiguration::class.java))
            .withPropertyValues("bluetape4k.aws.eventbridge.endpoint-override=http://localhost:4566")
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                messages shouldContain "region is required"
            }
    }

    @Test
    fun `shared defaults provide EventBridge region and endpoint override`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AwsAutoConfiguration::class.java,
                    EventBridgeAutoConfiguration::class.java,
                )
            )
            .withPropertyValues(
                "bluetape4k.aws.region=us-west-2",
                "bluetape4k.aws.eventbridge.endpoint-override=http://localhost:4566",
            )
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(EventBridgeAsyncClient::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `global and EventBridge async customizers are applied in order`() {
        EventBridgeCustomizerConfig.calls.clear()

        contextRunner
            .withUserConfiguration(EventBridgeCustomizerConfig::class.java)
            .run { context ->
                context.getBean(EventBridgeAsyncClient::class.java).shouldNotBeNull()
                EventBridgeCustomizerConfig.calls shouldBeEqualTo listOf("global:eventbridge", "eventbridge")
            }
    }

    @Test
    fun `EventBridge auto configuration backs off when EventBridge SDK is absent`() {
        contextRunner
            .withClassLoader(FilteredClassLoader("software.amazon.awssdk.services.eventbridge"))
            .run { context ->
                context.getBeansOfType(EventBridgeAsyncClient::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(EventBridgeOperations::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `default event bus property binds`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.eventbridge.default-event-bus-name=orders")
            .run { context ->
                context.getBean(EventBridgeProperties::class.java).defaultEventBusName shouldBeEqualTo "orders"
            }
    }

    @Configuration(proxyBeanMethods = false)
    internal class EventBridgeCustomizerConfig {
        @Bean
        fun globalAsyncCustomizer(): AwsAsyncClientCustomizer =
            RecordingAsyncCustomizer("global")

        @Bean
        fun eventBridgeClientCustomizer(): AwsClientCustomizer<EventBridgeAsyncClientBuilder> =
            AwsClientCustomizer { calls += "eventbridge" }

        private class RecordingAsyncCustomizer(
            private val name: String,
        ) : AwsAsyncClientCustomizer, Ordered {
            override fun customize(
                context: AwsClientCustomizationContext,
                builder: AwsAsyncClientBuilder<*, *>,
            ) {
                calls += "$name:${context.serviceName}"
            }

            override fun getOrder(): Int = 0
        }

        companion object {
            val calls: MutableList<String> = mutableListOf()
        }
    }

    private object NoopEventBridgeOperations : EventBridgeOperations {
        override suspend fun createEventBus(name: String): CreateEventBusResponse = error("not used")
        override suspend fun deleteEventBus(name: String): DeleteEventBusResponse = error("not used")
        override suspend fun putRule(
            name: String,
            eventBusName: String?,
            eventPattern: String?,
            scheduleExpression: String?,
            state: RuleState?,
            description: String?,
        ): PutRuleResponse = error("not used")

        override suspend fun deleteRule(
            name: String,
            eventBusName: String?,
            force: Boolean?,
        ): DeleteRuleResponse = error("not used")

        override suspend fun putTargets(
            rule: String,
            targets: List<Target>,
            eventBusName: String?,
        ): PutTargetsResponse = error("not used")

        override suspend fun removeTargets(
            rule: String,
            ids: List<String>,
            eventBusName: String?,
            force: Boolean?,
        ): RemoveTargetsResponse = error("not used")

        override suspend fun listRules(
            eventBusName: String?,
            namePrefix: String?,
            limit: Int?,
            nextToken: String?,
        ): ListRulesResponse = error("not used")

        override suspend fun listTargetsByRule(
            rule: String,
            eventBusName: String?,
            limit: Int?,
            nextToken: String?,
        ): ListTargetsByRuleResponse = error("not used")

        override suspend fun putEvents(entries: List<PutEventsRequestEntry>): PutEventsResponse = error("not used")
    }
}
