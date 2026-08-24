package io.bluetape4k.aws.spring.cloudwatch

import io.bluetape4k.logging.KLogging
import io.micrometer.cloudwatch2.CloudWatchConfig
import io.micrometer.cloudwatch2.CloudWatchMeterRegistry
import io.micrometer.core.instrument.Clock
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.util.NamedThreadFactory
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native Micrometer CloudWatch registry의 설정 adapter와 lifecycle을 구성합니다.
 *
 * Micrometer registry constructor가 즉시 publisher를 시작하므로, 설정과 filter를
 * 적용하는 동안에는 [CloudWatchRegistryConfig.enabled] gate를 닫아 둡니다.
 */
internal object CloudWatchMeterRegistryConfiguration: KLogging() {

    private const val NATIVE_PUBLISHER_THREAD_NAME = "bluetape4k-cloudwatch-metrics"
    private val HIGH_RESOLUTION_STEP: Duration = Duration.ofMinutes(1)

    fun create(
        cloudWatchAsyncClient: CloudWatchAsyncClient,
        properties: CloudWatchProperties,
        clock: Clock,
    ): CloudWatchMeterRegistry {
        val registryProperties = properties.micrometer.registry
        val namespace = registryProperties.namespace
            ?.takeIf(String::isNotBlank)
            ?: properties.namespace?.takeIf(String::isNotBlank)
            ?: error(
                "$CLOUDWATCH_PROPERTIES_PREFIX.micrometer.registry.namespace or " +
                    "$CLOUDWATCH_PROPERTIES_PREFIX.namespace must be configured when native registry is enabled.",
            )

        val config = CloudWatchRegistryConfig(namespace, registryProperties)
        val publisherThreadFactory = NamedThreadFactory(NATIVE_PUBLISHER_THREAD_NAME)
        val registry = CloudWatchMeterRegistry(config, clock, cloudWatchAsyncClient, publisherThreadFactory)

        registry.config().apply {
            if (registryProperties.commonTags.isNotEmpty()) {
                commonTags(registryProperties.commonTags.map { (key, value) -> Tag.of(key, value) })
            }
            registryProperties.filters.includes
                .takeIf(List<String>::isNotEmpty)
                ?.let { prefixes ->
                    meterFilter(MeterFilter.denyUnless { meter -> prefixes.any(meter.name::startsWith) })
                }
            registryProperties.filters.excludes
                .takeIf(List<String>::isNotEmpty)
                ?.let { prefixes ->
                    meterFilter(MeterFilter.deny { meter -> prefixes.any(meter.name::startsWith) })
                }
        }

        if (config.highResolution()) {
            log.warn(
                "CloudWatch native registry uses high-resolution storageResolution=1 " +
                    "(namespace={}, step={}); verify metric and API cost before production rollout.",
                namespace,
                registryProperties.step,
            )
        }

        config.enable()
        registry.start(publisherThreadFactory)
        return registry
    }

    @Suppress("DEPRECATION", "OVERRIDING_DEPRECATED_MEMBER")
    internal class CloudWatchRegistryConfig(
        private val namespaceValue: String,
        private val properties: CloudWatchProperties.Micrometer.Registry,
    ): CloudWatchConfig {
        private val enabledGate = AtomicBoolean(false)

        override fun prefix(): String = "cloudwatch"

        override fun get(key: String): String? = when (key) {
            "namespace" -> namespaceValue
            "step" -> properties.step.toString()
            "batchSize" -> properties.batchSize.toString()
            "readTimeout" -> properties.readTimeout.toString()
            "enabled" -> enabledGate.get().toString()
            else -> null
        }

        override fun namespace(): String = namespaceValue

        override fun step(): Duration = properties.step

        override fun batchSize(): Int = properties.batchSize

        override fun readTimeout(): Duration = properties.readTimeout

        override fun enabled(): Boolean = enabledGate.get()

        override fun highResolution(): Boolean = step() < HIGH_RESOLUTION_STEP

        fun enable() {
            enabledGate.set(true)
        }
    }
}
