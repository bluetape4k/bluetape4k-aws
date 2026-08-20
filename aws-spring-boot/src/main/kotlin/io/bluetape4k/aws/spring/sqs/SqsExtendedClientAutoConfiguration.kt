package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.aws.spring.ConditionalOnAwsEnabled
import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.s3.S3AutoConfiguration
import io.bluetape4k.aws.spring.s3.S3BoundedEncryptedReadOperations
import io.bluetape4k.aws.spring.s3.S3BoundedObjectReadOperations
import io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionIdentity
import io.bluetape4k.aws.spring.s3.S3ObjectMetadataOperations
import io.bluetape4k.aws.spring.s3.S3Operations
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional

/** SQS Extended Client를 명시적으로 활성화했을 때만 조립합니다. */
@AutoConfiguration(
    after = [
        AwsAutoConfiguration::class,
        SqsAutoConfiguration::class,
        S3AutoConfiguration::class,
        SqsMicrometerAutoConfiguration::class,
    ],
)
@ConditionalOnAwsEnabled
@ConditionalOnClass(
    name = [
        "software.amazon.awssdk.services.sqs.SqsAsyncClient",
        "software.amazon.awssdk.services.s3.S3AsyncClient",
    ],
)
@ConditionalOnProperty(
    prefix = "bluetape4k.aws.sqs.extended",
    name = ["enabled"],
    havingValue = "true",
)
@EnableConfigurationProperties(SqsExtendedClientProperties::class)
class SqsExtendedClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SqsExtendedClientOperations::class)
    @Conditional(SqsExtendedLifecycleBudgetCondition::class)
    @ConditionalOnBean(
        SqsFullRequestOperations::class,
        S3Operations::class,
    )
    fun sqsExtendedClient(
        sqsOperations: SqsFullRequestOperations,
        s3Operations: S3Operations,
        boundedS3Operations: ObjectProvider<S3BoundedObjectReadOperations>,
        metadataOperations: ObjectProvider<S3ObjectMetadataOperations>,
        encryptedOperations: ObjectProvider<S3BoundedEncryptedReadOperations>,
        encryptionIdentity: ObjectProvider<S3ClientSideEncryptionIdentity>,
        metrics: ObjectProvider<SqsExtendedClientMetrics>,
        properties: SqsExtendedClientProperties,
    ): SqsExtendedClient {
        val metadata = metadataOperations.getIfAvailable()
        val bounded = boundedS3Operations.getIfAvailable()
        val encrypted = encryptedOperations.getIfAvailable()
        val identity = encryptionIdentity.getIfAvailable()
        validateCapabilities(properties, bounded, metadata, encrypted, identity)
        return SqsExtendedClient(
            sqsOperations = sqsOperations,
            s3Operations = s3Operations,
            boundedS3Operations = bounded,
            s3MetadataOperations = metadata,
            encryptedS3Operations = encrypted,
            encryptionIdentity = identity,
            properties = properties,
            metrics = metrics.getIfAvailable(),
        )
    }

    @Bean
    @ConditionalOnClass(MeterRegistry::class)
    @ConditionalOnBean(MeterRegistry::class)
    @ConditionalOnMissingBean(SqsExtendedClientMetrics::class)
    fun sqsExtendedClientMetrics(meterRegistry: MeterRegistry): SqsExtendedClientMetrics =
        SqsExtendedClientMetrics(meterRegistry)

    @Bean
    @ConditionalOnBean(SqsExtendedClient::class)
    @ConditionalOnMissingBean(SqsExtendedClientLifecycle::class)
    @Conditional(SqsExtendedLifecycleBudgetCondition::class)
    fun sqsExtendedClientLifecycle(
        client: SqsExtendedClient,
        properties: SqsExtendedClientProperties,
    ): SqsExtendedClientLifecycle = SqsExtendedClientLifecycle(client, properties)

    private fun validateCapabilities(
        properties: SqsExtendedClientProperties,
        bounded: S3BoundedObjectReadOperations?,
        metadata: S3ObjectMetadataOperations?,
        encrypted: S3BoundedEncryptedReadOperations?,
        identity: S3ClientSideEncryptionIdentity?,
    ) {
        val policies = properties.queues.values.map { it.policy } +
            listOfNotNull(properties.defaultPolicy.takeIf { properties.defaultQueueUrls.isNotEmpty() })
        val invalid = buildList {
            if (policies.any { it.deleteOnAck } && metadata == null) add(Unit)
            if (properties.consumerEnabled && policies.any { !it.encryption.enabled } && bounded == null) add(Unit)
            policies.filter { it.encryption.enabled }.forEach { policy ->
                val identityMismatch = identity?.keyFingerprint != policy.encryption.keyFingerprint
                if (encrypted == null || identityMismatch) {
                    add(Unit)
                }
            }
        }
        if (invalid.isNotEmpty()) throw SqsExtendedConfigurationException.create()
    }
}
