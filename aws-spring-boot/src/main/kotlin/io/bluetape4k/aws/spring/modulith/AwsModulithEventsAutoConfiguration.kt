package io.bluetape4k.aws.spring.modulith

import io.bluetape4k.aws.spring.sns.SnsHttpMessageVerifier
import io.bluetape4k.aws.spring.sns.SnsOperations
import io.bluetape4k.aws.spring.sqs.SqsFullRequestOperations
import io.bluetape4k.aws.spring.sqs.SqsOperations
import io.bluetape4k.aws.spring.sqs.SqsProperties
import io.bluetape4k.aws.spring.sqs.SqsQueueAttributesOperations
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.ImportSelector
import org.springframework.core.type.AnnotationMetadata
import org.springframework.modulith.events.EventExternalizationConfiguration
import org.springframework.modulith.events.core.EventSerializer
import org.springframework.modulith.events.support.EventExternalizationTransport
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import java.time.Clock

/** Optional Modulith classpath를 name-only로 확인하는 AWS event adapter 진입점입니다. */
@AutoConfiguration(
    afterName = [
        "io.bluetape4k.aws.spring.sns.SnsAutoConfiguration",
        "io.bluetape4k.aws.spring.sns.SnsHttpMessageVerificationAutoConfiguration",
        "io.bluetape4k.aws.spring.sqs.SqsAutoConfiguration",
    ]
)
@ConditionalOnClass(
    name = [
        "org.springframework.modulith.events.support.EventExternalizationTransport",
        "org.springframework.modulith.events.core.EventSerializer",
        "org.springframework.modulith.events.support.EventExternalizerModuleListener",
    ]
)
@ConditionalOnProperty(
    prefix = "bluetape4k.aws.modulith.events",
    name = ["enabled"],
    havingValue = "true",
)
@Import(AwsModulithEventsImportSelector::class)
class AwsModulithEventsAutoConfiguration

/** Outer condition 통과 뒤에만 optional-type configuration 이름을 반환합니다. */
internal class AwsModulithEventsImportSelector : ImportSelector {
    override fun selectImports(importingClassMetadata: AnnotationMetadata): Array<String> =
        arrayOf(MODULITH_CONFIGURATION)

    private companion object {
        const val MODULITH_CONFIGURATION =
            "io.bluetape4k.aws.spring.modulith.AwsModulithEventsConfiguration"
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AwsModulithEventsProperties::class)
@Import(
    AwsModulithEventCodecConfiguration::class,
    AwsModulithEventProducerConfiguration::class,
    AwsModulithSnsPublisherConfiguration::class,
    AwsModulithSqsPublisherConfiguration::class,
    AwsModulithDirectConsumerSourceConfiguration::class,
    AwsModulithSnsConsumerSourceConfiguration::class,
    AwsModulithSqsConsumerConfiguration::class,
)
internal class AwsModulithEventsConfiguration

@Configuration(proxyBeanMethods = false)
@Conditional(AwsModulithCodecRequiredCondition::class)
internal class AwsModulithEventCodecConfiguration {

    @Bean
    @ConditionalOnMissingBean(AwsModulithEventCodec::class)
    fun awsModulithEventCodec(
        properties: AwsModulithEventsProperties,
        registry: ObjectProvider<AwsModulithEventTypeRegistry>,
        serializer: ObjectProvider<EventSerializer>,
    ): AwsModulithEventCodec {
        properties.validate()
        return DefaultAwsModulithEventCodec(
            registry = registry.getIfAvailable() ?: throw AwsModulithConfigurationException(),
            eventSerializer = serializer.getIfAvailable() ?: throw AwsModulithConfigurationException(),
            maxSerializedPayloadBytes = properties.producer.maxSerializedPayloadBytes,
            maxEnvelopeBytes = properties.producer.maxEnvelopeBytes,
        )
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "bluetape4k.aws.modulith.events.producer",
    name = ["enabled"],
    havingValue = "true",
)
internal class AwsModulithEventProducerConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(EventExternalizationTransport::class)
    fun awsModulithEventExternalizationTransport(
        properties: AwsModulithEventsProperties,
        codec: ObjectProvider<AwsModulithEventCodec>,
        beanFactory: BeanFactory,
    ): EventExternalizationTransport {
        properties.validate()
        val publishers = buildMap {
            addPublisher(beanFactory, SNS_PUBLISHER_BEAN, AwsModulithTargetService.SNS)
            addPublisher(beanFactory, SQS_PUBLISHER_BEAN, AwsModulithTargetService.SQS)
        }
        if (!publishers.keys.containsAll(properties.targets.values.map(AwsModulithEventsProperties.Target::service))) {
            throw AwsModulithConfigurationException()
        }
        return AwsModulithEventExternalizationTransport(
            targets = properties.targets,
            codec = codec.getIfAvailable() ?: throw AwsModulithConfigurationException(),
            publishers = publishers,
            maxInFlight = properties.producer.maxInFlight,
            shutdownTimeout = properties.producer.shutdownTimeout,
        )
    }

    private fun MutableMap<AwsModulithTargetService, AwsModulithTargetPublisher>.addPublisher(
        beanFactory: BeanFactory,
        beanName: String,
        service: AwsModulithTargetService,
    ) {
        if (beanFactory.containsBean(beanName)) {
            put(service, beanFactory.getBean(beanName, AwsModulithTargetPublisher::class.java))
        }
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "bluetape4k.aws.modulith.events.producer",
    name = ["enabled"],
    havingValue = "true",
)
@ConditionalOnClass(
    name = [
        "io.bluetape4k.aws.spring.sns.SnsOperations",
        "software.amazon.awssdk.services.sns.model.PublishRequest",
    ]
)
internal class AwsModulithSnsPublisherConfiguration {

    @Bean(name = [SNS_PUBLISHER_BEAN])
    @ConditionalOnBean(SnsOperations::class)
    fun awsModulithSnsTargetPublisher(operations: SnsOperations): AwsModulithTargetPublisher =
        AwsModulithSnsTargetPublisher(operations)
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "bluetape4k.aws.modulith.events.producer",
    name = ["enabled"],
    havingValue = "true",
)
@ConditionalOnClass(
    name = [
        "io.bluetape4k.aws.spring.sqs.SqsOperations",
        "software.amazon.awssdk.services.sqs.model.SendMessageRequest",
    ]
)
internal class AwsModulithSqsPublisherConfiguration {

    @Bean(name = [SQS_PUBLISHER_BEAN])
    @ConditionalOnBean(SqsOperations::class)
    fun awsModulithSqsTargetPublisher(
        operations: SqsOperations,
        properties: AwsModulithEventsProperties,
    ): AwsModulithTargetPublisher {
        if (operations !is SqsFullRequestOperations) throw AwsModulithConfigurationException()
        val aliases = properties.targets
            .filterValues { it.service == AwsModulithTargetService.SQS }
            .keys
        return AwsModulithSqsTargetPublisher(operations, aliases)
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "bluetape4k.aws.modulith.events.consumer",
    name = ["enabled"],
    havingValue = "true",
)
@ConditionalOnProperty(
    prefix = "bluetape4k.aws.modulith.events.consumer",
    name = ["source-mode"],
    havingValue = "DIRECT",
)
internal class AwsModulithDirectConsumerSourceConfiguration {

    @Bean
    @ConditionalOnMissingBean(AwsModulithInboundSourceDecoder::class)
    fun awsModulithInboundSourceDecoder(
        properties: AwsModulithEventsProperties,
        codec: AwsModulithEventCodec,
    ): AwsModulithInboundSourceDecoder =
        DefaultAwsModulithInboundSourceDecoder(
            sourceMode = AwsModulithSourceMode.DIRECT,
            expectedTopicArns = properties.consumer.expectedTopicArns,
            codec = codec,
        )
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "bluetape4k.aws.modulith.events.consumer",
    name = ["enabled"],
    havingValue = "true",
)
@ConditionalOnProperty(
    prefix = "bluetape4k.aws.modulith.events.consumer",
    name = ["source-mode"],
    havingValue = "SNS",
)
@ConditionalOnClass(name = ["software.amazon.awssdk.messagemanager.sns.SnsMessageManager"])
internal class AwsModulithSnsConsumerSourceConfiguration {

    @Bean
    @ConditionalOnMissingBean(AwsModulithInboundSourceDecoder::class)
    @ConditionalOnBean(SnsHttpMessageVerifier::class)
    fun awsModulithInboundSourceDecoder(
        properties: AwsModulithEventsProperties,
        codec: AwsModulithEventCodec,
        verifier: SnsHttpMessageVerifier,
    ): AwsModulithInboundSourceDecoder =
        DefaultAwsModulithInboundSourceDecoder(
            sourceMode = AwsModulithSourceMode.SNS,
            expectedTopicArns = properties.consumer.expectedTopicArns,
            codec = codec,
            snsVerifier = DefaultAwsModulithSnsNotificationVerifier(verifier),
        )
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "bluetape4k.aws.modulith.events.consumer",
    name = ["enabled"],
    havingValue = "true",
)
@ConditionalOnClass(
    name = [
        "io.bluetape4k.aws.spring.sqs.SqsOperations",
        "software.amazon.awssdk.services.sqs.model.Message",
    ]
)
@EnableConfigurationProperties(SqsProperties::class)
internal class AwsModulithSqsConsumerConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(AwsModulithEventIdempotencyStore::class)
    fun awsModulithEventIdempotencyStore(
        properties: AwsModulithEventsProperties,
    ): AwsModulithEventIdempotencyStore =
        InMemoryAwsModulithEventIdempotencyStore(properties.consumer.idempotency)

    @Bean
    @ConditionalOnMissingBean(AwsModulithMetrics::class)
    fun awsModulithMetrics(registry: ObjectProvider<MeterRegistry>): AwsModulithMetrics =
        AwsModulithMetrics(registry.getIfAvailable())

    @Bean
    @ConditionalOnMissingBean(AwsModulithSqsEventConsumer::class)
    fun awsModulithSqsEventConsumer(
        properties: AwsModulithEventsProperties,
        sqsProperties: SqsProperties,
        operations: ObjectProvider<SqsOperations>,
        decoder: ObjectProvider<AwsModulithInboundSourceDecoder>,
        registry: ObjectProvider<AwsModulithEventTypeRegistry>,
        store: AwsModulithEventIdempotencyStore,
        externalization: ObjectProvider<EventExternalizationConfiguration>,
        eventPublisher: ApplicationEventPublisher,
        metrics: AwsModulithMetrics,
        clock: ObjectProvider<Clock>,
    ): AwsModulithSqsEventConsumer {
        properties.validate()
        val sqsOperations = operations.required()
        validateRedrivePolicy(properties.consumer, sqsOperations)
        return AwsModulithSqsEventConsumer(
            sourceDecoder = decoder.required(),
            registry = registry.required(),
            store = store,
            externalization = externalization.required(),
            eventPublisher = eventPublisher,
            properties = properties.consumer,
            metrics = metrics,
            clock = clock.getIfAvailable { Clock.systemUTC() },
            cleanupTimeout = AwsModulithSqsEventConsumer.cleanupTimeout(
                sqsProperties.listener.stopTimeoutMillis,
                properties.consumer.idempotency.leaseDuration,
            ),
        )
    }

    @Bean
    @ConditionalOnMissingBean(AwsModulithSqsEventListener::class)
    fun awsModulithSqsEventListener(consumer: AwsModulithSqsEventConsumer): AwsModulithSqsEventListener =
        AwsModulithSqsEventListener(consumer)

    private fun validateRedrivePolicy(
        properties: AwsModulithEventsProperties.Consumer,
        operations: SqsOperations,
    ) {
        if (!properties.redriveRequired) return
        val attributes = operations as? SqsQueueAttributesOperations
            ?: configurationFailure()
        val queue = properties.queue ?: configurationFailure()
        val redrive = try {
            runBlocking(Dispatchers.IO) {
                val queueUrl = operations.getQueueUrl(queue)
                attributes.getQueueAttributes(queueUrl, listOf(QueueAttributeName.REDRIVE_POLICY))
                    .get(QueueAttributeName.REDRIVE_POLICY)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            configurationFailure()
        }
        if (redrive.isNullOrBlank()) configurationFailure()
    }
}

internal class AwsModulithCodecRequiredCondition :
    AnyNestedCondition(ConfigurationPhase.REGISTER_BEAN) {

    @ConditionalOnProperty(
        prefix = "bluetape4k.aws.modulith.events.producer",
        name = ["enabled"],
        havingValue = "true",
    )
    @ConditionalOnMissingBean(EventExternalizationTransport::class)
    internal class BuiltInProducerEnabled

    @ConditionalOnProperty(
        prefix = "bluetape4k.aws.modulith.events.consumer",
        name = ["enabled"],
        havingValue = "true",
    )
    internal class ConsumerEnabled
}

private const val SNS_PUBLISHER_BEAN = "awsModulithSnsTargetPublisher"
private const val SQS_PUBLISHER_BEAN = "awsModulithSqsTargetPublisher"

private fun <T : Any> ObjectProvider<T>.required(): T = getIfAvailable() ?: configurationFailure()

private fun configurationFailure(): Nothing = throw AwsModulithConfigurationException()
