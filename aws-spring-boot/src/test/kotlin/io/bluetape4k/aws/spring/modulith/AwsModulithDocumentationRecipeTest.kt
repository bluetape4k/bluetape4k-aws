package io.bluetape4k.aws.spring.modulith

import io.bluetape4k.aws.spring.sns.SnsHttpMessageVerificationAutoConfiguration
import io.bluetape4k.aws.spring.sns.SnsOperations
import io.bluetape4k.aws.spring.sqs.SqsCoroutinesTemplate
import io.bluetape4k.aws.spring.sqs.SqsFullRequestOperations
import io.bluetape4k.aws.spring.sqs.SqsOperations
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.modulith.events.EventExternalizationConfiguration
import org.springframework.modulith.events.core.EventSerializer
import org.springframework.modulith.events.support.EventExternalizationTransport
import software.amazon.awssdk.services.sqs.model.QueueAttributeName

class AwsModulithDocumentationRecipeTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AwsModulithEventsAutoConfiguration::class.java))
        .withBean(AwsModulithEventTypeRegistry::class.java, ::eventRegistry)
        .withBean(EventSerializer::class.java, { RecipeEventSerializer })

    @Test
    fun `SNS producer-only recipe starts`() {
        contextRunner
            .withBean(SnsOperations::class.java, { mockk(relaxed = true) })
            .withPropertyValues(
                "bluetape4k.aws.modulith.events.enabled=true",
                "bluetape4k.aws.modulith.events.producer.enabled=true",
                "bluetape4k.aws.modulith.events.targets.order-events.service=sns",
                "bluetape4k.aws.modulith.events.targets.order-events.destination=order-events",
            )
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(EventExternalizationTransport::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `SQS FIFO producer recipe starts`() {
        contextRunner
            .withBean(SqsOperations::class.java, { mockk<SqsFullRequestOperations>(relaxed = true) })
            .withPropertyValues(
                "bluetape4k.aws.modulith.events.enabled=true",
                "bluetape4k.aws.modulith.events.producer.enabled=true",
                "bluetape4k.aws.modulith.events.targets.order-events-fifo.service=sqs",
                "bluetape4k.aws.modulith.events.targets.order-events-fifo.destination=order-events.fifo",
            )
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(EventExternalizationTransport::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `DIRECT consumer recipe starts with redrive policy`() {
        consumerRunner()
            .withPropertyValues(
                "bluetape4k.aws.modulith.events.enabled=true",
                "bluetape4k.aws.modulith.events.consumer.enabled=true",
                "bluetape4k.aws.modulith.events.consumer.queue=direct-order-events",
                "bluetape4k.aws.modulith.events.consumer.source-mode=direct",
                "bluetape4k.aws.modulith.events.consumer.redrive-required=true",
            )
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(AwsModulithSqsEventConsumer::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `SNS consumer recipe starts with verifier and redrive policy`() {
        snsConsumerRunner()
            .withPropertyValues(
                "bluetape4k.aws.modulith.events.enabled=true",
                "bluetape4k.aws.sns.region=ap-northeast-2",
                "bluetape4k.aws.modulith.events.consumer.enabled=true",
                "bluetape4k.aws.modulith.events.consumer.queue=sns-order-events",
                "bluetape4k.aws.modulith.events.consumer.source-mode=sns",
                "bluetape4k.aws.modulith.events.consumer.expected-topic-arns[0]=" +
                    "arn:aws:sns:ap-northeast-2:123456789012:order-events",
                "bluetape4k.aws.modulith.events.consumer.redrive-required=true",
            )
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(AwsModulithSqsEventConsumer::class.java).size shouldBeEqualTo 1
            }
    }

    private fun consumerRunner(): ApplicationContextRunner {
        val operations = mockk<SqsCoroutinesTemplate>()
        coEvery { operations.getQueueUrl(any()) } returns QUEUE_URL
        coEvery {
            operations.getQueueAttributes(QUEUE_URL, listOf(QueueAttributeName.REDRIVE_POLICY))
        } returns mapOf(QueueAttributeName.REDRIVE_POLICY to "{\"maxReceiveCount\":\"3\"}")

        return contextRunner
            .withBean(SqsOperations::class.java, { operations })
            .withBean(
                EventExternalizationConfiguration::class.java,
                { EventExternalizationConfiguration.disabled() },
            )
    }

    private fun snsConsumerRunner(): ApplicationContextRunner = consumerRunner()
        .withConfiguration(
            AutoConfigurations.of(SnsHttpMessageVerificationAutoConfiguration::class.java)
        )

    private fun eventRegistry(): AwsModulithEventTypeRegistry = AwsModulithEventTypeRegistry.of(
        AwsModulithEventTypeRegistration(
            type = "order.created",
            version = 1,
            eventClass = RecipeEvent::class.java,
            eventId = RecipeEvent::id,
        )
    )

    private data class RecipeEvent(val id: String)

    private object RecipeEventSerializer : EventSerializer {
        override fun serialize(event: Any): Any = "{\"id\":\"${(event as RecipeEvent).id}\"}"

        override fun <T : Any> deserialize(serialized: Any, type: Class<T>): T =
            type.cast(RecipeEvent("decoded"))
    }

    private companion object {
        const val QUEUE_URL = "https://sqs.ap-northeast-2.amazonaws.com/123456789012/order-events"
    }
}
