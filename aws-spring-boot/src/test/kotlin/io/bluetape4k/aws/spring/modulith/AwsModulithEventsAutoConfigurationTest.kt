package io.bluetape4k.aws.spring.modulith

import io.bluetape4k.aws.spring.sns.SnsHttpMessageVerifier
import io.bluetape4k.aws.spring.sns.SnsHttpMessageVerificationAutoConfiguration
import io.bluetape4k.aws.spring.sns.SnsOperations
import io.bluetape4k.aws.spring.sqs.SqsFullRequestOperations
import io.bluetape4k.aws.spring.sqs.SqsOperations
import io.bluetape4k.aws.spring.sqs.SqsCoroutinesTemplate
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.modulith.events.EventExternalizationConfiguration
import org.springframework.modulith.events.RoutingTarget
import org.springframework.modulith.events.core.EventSerializer
import org.springframework.modulith.events.support.EventExternalizationTransport
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import java.time.Duration
import java.util.concurrent.CompletionException

class AwsModulithEventsAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AwsModulithEventsAutoConfiguration::class.java))

    @Test
    fun `root opt-in is disabled by default`() {
        contextRunner.run { context ->
            context.startupFailure.shouldBeNull()
            context.getBeansOfType(AwsModulithEventsProperties::class.java).size shouldBeEqualTo 0
        }
    }

    @Test
    fun `root opt-in with both directions disabled registers only properties`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.modulith.events.enabled=true")
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(AwsModulithEventsProperties::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(AwsModulithSqsEventConsumer::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `consumer source settings stay inert while consumer is disabled`() {
        contextRunner
            .withPropertyValues(
                "bluetape4k.aws.modulith.events.enabled=true",
                "bluetape4k.aws.modulith.events.consumer.source-mode=sns",
                "bluetape4k.aws.modulith.events.consumer.expected-topic-arns[0]=" +
                    "arn:aws:sns:us-east-1:000000000000:events",
            )
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(AwsModulithInboundSourceDecoder::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `producer without registry fails with bounded configuration code`() {
        contextRunner
            .withPropertyValues(
                "bluetape4k.aws.modulith.events.enabled=true",
                "bluetape4k.aws.modulith.events.producer.enabled=true",
                "bluetape4k.aws.modulith.events.targets.events.service=sqs",
                "bluetape4k.aws.modulith.events.targets.events.destination=events",
            )
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                failureMessages(context.startupFailure) shouldContain AwsModulithDiagnosticCode.CONFIGURATION.value
            }
    }

    @Test
    fun `producer without serializer fails with bounded configuration code`() {
        contextRunner
            .withBean(AwsModulithEventTypeRegistry::class.java, { eventRegistry() })
            .withBean(SqsOperations::class.java, { mockk<SqsFullRequestOperations>(relaxed = true) })
            .withPropertyValues(*sqsProducerProperties())
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                failureMessages(context.startupFailure) shouldContain AwsModulithDiagnosticCode.CONFIGURATION.value
            }
    }

    @Test
    fun `SQS producer creates transport only with full request capability`() {
        val operations = mockk<SqsFullRequestOperations>(relaxed = true)

        configuredRunner()
            .withBean(SqsOperations::class.java, { operations })
            .withPropertyValues(*sqsProducerProperties())
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(EventExternalizationTransport::class.java).size shouldBeEqualTo 1
            }

        configuredRunner()
            .withBean(SqsOperations::class.java, { mockk(relaxed = true) })
            .withPropertyValues(*sqsProducerProperties())
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                failureMessages(context.startupFailure) shouldContain AwsModulithDiagnosticCode.CONFIGURATION.value
            }
    }

    @Test
    fun `SNS only producer does not instantiate an empty SQS publisher`() {
        configuredRunner()
            .withBean(SnsOperations::class.java, { mockk(relaxed = true) })
            .withBean(SqsOperations::class.java, { mockk<SqsFullRequestOperations>(relaxed = true) })
            .withPropertyValues(
                "bluetape4k.aws.modulith.events.enabled=true",
                "bluetape4k.aws.modulith.events.producer.enabled=true",
                "bluetape4k.aws.modulith.events.targets.events.service=sns",
                "bluetape4k.aws.modulith.events.targets.events.destination=events",
            )
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(EventExternalizationTransport::class.java).size shouldBeEqualTo 1
                context.containsBean("awsModulithSqsTargetPublisher") shouldBeEqualTo false
            }
    }

    @Test
    fun `custom transport backs off producer and does not suppress DIRECT consumer`() {
        val transport = EventExternalizationTransport { _, _ ->
            java.util.concurrent.CompletableFuture.completedFuture(null)
        }
        val operations = mockk<SqsFullRequestOperations>(relaxed = true)

        configuredRunner()
            .withBean(EventExternalizationTransport::class.java, { transport })
            .withBean(SqsOperations::class.java, { operations })
            .withBean(EventExternalizationConfiguration::class.java, { EventExternalizationConfiguration.disabled() })
            .withPropertyValues(*directConsumerProperties(redriveRequired = false))
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBean(EventExternalizationTransport::class.java) shouldBeSameInstanceAs transport
                context.getBeansOfType(AwsModulithSqsEventConsumer::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(AwsModulithSqsEventListener::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `custom transport fully backs off producer codec requirements`() {
        val transport = EventExternalizationTransport { _, _ ->
            java.util.concurrent.CompletableFuture.completedFuture(null)
        }

        contextRunner
            .withBean(EventExternalizationTransport::class.java, { transport })
            .withPropertyValues(
                "bluetape4k.aws.modulith.events.enabled=true",
                "bluetape4k.aws.modulith.events.producer.enabled=true",
                "bluetape4k.aws.modulith.events.targets.events.service=sqs",
                "bluetape4k.aws.modulith.events.targets.events.destination=events",
            )
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBean(EventExternalizationTransport::class.java) shouldBeSameInstanceAs transport
                context.getBeansOfType(AwsModulithEventCodec::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `custom transport does not bypass the producer target invariant`() {
        val transport = EventExternalizationTransport { _, _ ->
            java.util.concurrent.CompletableFuture.completedFuture(null)
        }

        contextRunner
            .withBean(EventExternalizationTransport::class.java, { transport })
            .withPropertyValues(
                "bluetape4k.aws.modulith.events.enabled=true",
                "bluetape4k.aws.modulith.events.producer.enabled=true",
            )
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                failureMessages(context.startupFailure) shouldContain AwsModulithDiagnosticCode.CONFIGURATION.value
            }
    }

    @Test
    fun `custom idempotency store backs off the owned in-memory store`() {
        val store = mockk<AwsModulithEventIdempotencyStore>(relaxed = true)

        configuredRunner()
            .withBean(AwsModulithEventIdempotencyStore::class.java, { store })
            .withBean(SqsOperations::class.java, { mockk<SqsFullRequestOperations>(relaxed = true) })
            .withBean(EventExternalizationConfiguration::class.java, { EventExternalizationConfiguration.disabled() })
            .withPropertyValues(*directConsumerProperties(redriveRequired = false))
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(AwsModulithEventIdempotencyStore::class.java).size shouldBeEqualTo 1
                context.getBean(AwsModulithEventIdempotencyStore::class.java) shouldBeSameInstanceAs store
            }
    }

    @Test
    fun `context closes the transport and store created by auto configuration`() {
        val operations = mockk<SqsFullRequestOperations>(relaxed = true)

        configuredRunner()
            .withBean(SqsOperations::class.java, { operations })
            .withBean(
                EventExternalizationConfiguration::class.java,
                { EventExternalizationConfiguration.disabled() },
            )
            .withPropertyValues(
                *sqsProducerProperties(),
                *directConsumerProperties(redriveRequired = false),
            )
            .run { context ->
                val transport = context.getBean(EventExternalizationTransport::class.java)
                val store = context.getBean(InMemoryAwsModulithEventIdempotencyStore::class.java)

                context.close()

                runCatching {
                    transport.externalize(TestEvent("closed"), RoutingTarget.forTarget("events").withoutKey()).join()
                }.exceptionOrNull()
                    .shouldBeInstanceOf<CompletionException>()
                    .cause.shouldBeInstanceOf<AwsModulithProducerClosedException>()
                runCatching {
                    runBlocking {
                        store.claim(AwsModulithEventKey("test.event", "closed"), Duration.ofMinutes(1))
                    }
                }.exceptionOrNull().shouldBeInstanceOf<AwsModulithClaimMutationException>()
            }
    }

    @Test
    fun `required redrive rejects missing capability and missing policy`() {
        configuredRunner()
            .withBean(SqsOperations::class.java, { mockk<SqsFullRequestOperations>(relaxed = true) })
            .withBean(EventExternalizationConfiguration::class.java, { EventExternalizationConfiguration.disabled() })
            .withPropertyValues(*directConsumerProperties(redriveRequired = true))
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                failureMessages(context.startupFailure) shouldContain AwsModulithDiagnosticCode.CONFIGURATION.value
            }

        val operations = mockk<SqsCoroutinesTemplate>()
        coEvery { operations.getQueueUrl("events") } returns QUEUE_URL
        coEvery {
            operations.getQueueAttributes(QUEUE_URL, listOf(QueueAttributeName.REDRIVE_POLICY))
        } returns emptyMap()

        configuredRunner()
            .withBean(SqsOperations::class.java, { operations })
            .withBean(EventExternalizationConfiguration::class.java, { EventExternalizationConfiguration.disabled() })
            .withPropertyValues(*directConsumerProperties(redriveRequired = true))
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                failureMessages(context.startupFailure) shouldContain AwsModulithDiagnosticCode.CONFIGURATION.value
            }
    }

    @Test
    fun `required redrive accepts a nonblank queue policy`() {
        val operations = mockk<SqsCoroutinesTemplate>()
        coEvery { operations.getQueueUrl("events") } returns QUEUE_URL
        coEvery {
            operations.getQueueAttributes(QUEUE_URL, listOf(QueueAttributeName.REDRIVE_POLICY))
        } returns mapOf(QueueAttributeName.REDRIVE_POLICY to "{\"maxReceiveCount\":\"3\"}")

        configuredRunner()
            .withBean(SqsOperations::class.java, { operations })
            .withBean(EventExternalizationConfiguration::class.java, { EventExternalizationConfiguration.disabled() })
            .withPropertyValues(*directConsumerProperties(redriveRequired = true))
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(AwsModulithSqsEventConsumer::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `redrive lookup failure is sanitized as bounded configuration failure`() {
        val operations = mockk<SqsCoroutinesTemplate>()
        coEvery { operations.getQueueUrl("events") } throws IllegalStateException("sensitive provider failure")

        configuredRunner()
            .withBean(SqsOperations::class.java, { operations })
            .withBean(EventExternalizationConfiguration::class.java, { EventExternalizationConfiguration.disabled() })
            .withPropertyValues(*directConsumerProperties(redriveRequired = true))
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                val messages = failureMessages(context.startupFailure)
                messages shouldContain AwsModulithDiagnosticCode.CONFIGURATION.value
                ("sensitive provider failure" in messages) shouldBeEqualTo false
            }
    }

    @Test
    fun `SNS consumer requires and accepts an explicit verifier`() {
        val operations = mockk<SqsFullRequestOperations>(relaxed = true)
        val externalization = EventExternalizationConfiguration.disabled()

        configuredRunner()
            .withBean(SqsOperations::class.java, { operations })
            .withBean(EventExternalizationConfiguration::class.java, { externalization })
            .withPropertyValues(*snsConsumerProperties())
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                failureMessages(context.startupFailure) shouldContain AwsModulithDiagnosticCode.CONFIGURATION.value
            }

        configuredRunner()
            .withBean(SqsOperations::class.java, { operations })
            .withBean(EventExternalizationConfiguration::class.java, { externalization })
            .withBean(SnsHttpMessageVerifier::class.java, { mockk(relaxed = true) })
            .withPropertyValues(*snsConsumerProperties())
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(AwsModulithSqsEventConsumer::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `SNS verification auto configuration is ordered before the consumer adapter`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    SnsHttpMessageVerificationAutoConfiguration::class.java,
                    AwsModulithEventsAutoConfiguration::class.java,
                )
            )
            .withBean(AwsModulithEventTypeRegistry::class.java, { eventRegistry() })
            .withBean(EventSerializer::class.java, { TestEventSerializer })
            .withBean(SqsOperations::class.java, { mockk<SqsFullRequestOperations>(relaxed = true) })
            .withBean(
                EventExternalizationConfiguration::class.java,
                { EventExternalizationConfiguration.disabled() },
            )
            .withPropertyValues(*snsConsumerProperties())
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(SnsHttpMessageVerifier::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(AwsModulithSqsEventConsumer::class.java).size shouldBeEqualTo 1
            }
    }

    private fun configuredRunner(): ApplicationContextRunner = contextRunner
        .withBean(AwsModulithEventTypeRegistry::class.java, { eventRegistry() })
        .withBean(EventSerializer::class.java, { TestEventSerializer })

    private fun eventRegistry(): AwsModulithEventTypeRegistry = AwsModulithEventTypeRegistry.of(
        AwsModulithEventTypeRegistration(
            type = "test.event",
            version = 1,
            eventClass = TestEvent::class.java,
            eventId = TestEvent::id,
        )
    )

    private fun sqsProducerProperties(): Array<String> = arrayOf(
        "bluetape4k.aws.modulith.events.enabled=true",
        "bluetape4k.aws.modulith.events.producer.enabled=true",
        "bluetape4k.aws.modulith.events.targets.events.service=sqs",
        "bluetape4k.aws.modulith.events.targets.events.destination=events",
    )

    private fun directConsumerProperties(redriveRequired: Boolean): Array<String> = arrayOf(
        "bluetape4k.aws.modulith.events.enabled=true",
        "bluetape4k.aws.modulith.events.consumer.enabled=true",
        "bluetape4k.aws.modulith.events.consumer.queue=events",
        "bluetape4k.aws.modulith.events.consumer.source-mode=direct",
        "bluetape4k.aws.modulith.events.consumer.redrive-required=$redriveRequired",
    )

    private fun snsConsumerProperties(): Array<String> = arrayOf(
        "bluetape4k.aws.modulith.events.enabled=true",
        "bluetape4k.aws.sns.region=us-east-1",
        "bluetape4k.aws.modulith.events.consumer.enabled=true",
        "bluetape4k.aws.modulith.events.consumer.queue=events",
        "bluetape4k.aws.modulith.events.consumer.source-mode=sns",
        "bluetape4k.aws.modulith.events.consumer.expected-topic-arns[0]=" +
            "arn:aws:sns:us-east-1:000000000000:events",
        "bluetape4k.aws.modulith.events.consumer.redrive-required=false",
    )

    private fun failureMessages(failure: Throwable?): String =
        generateSequence(failure) { it.cause }
            .mapNotNull { it.message }
            .joinToString("\n")

    private data class TestEvent(val id: String)

    private object TestEventSerializer : EventSerializer {
        override fun serialize(event: Any): Any = "{\"id\":\"${(event as TestEvent).id}\"}"

        override fun <T : Any> deserialize(serialized: Any, type: Class<T>): T =
            type.cast(TestEvent("decoded"))
    }

    companion object {
        private const val QUEUE_URL = "https://sqs.us-east-1.amazonaws.com/000000000000/events"
    }
}
