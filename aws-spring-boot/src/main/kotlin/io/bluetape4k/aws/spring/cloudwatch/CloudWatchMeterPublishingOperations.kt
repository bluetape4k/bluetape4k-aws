package io.bluetape4k.aws.spring.cloudwatch

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.micrometer.core.instrument.Measurement
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Statistic
import software.amazon.awssdk.services.cloudwatch.model.Dimension
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit
import java.util.Locale

private const val CLOUDWATCH_MAX_DIMENSIONS = 30

/**
 * Publishes selected Micrometer meter snapshots to CloudWatch through [CloudWatchOperations].
 *
 * ## Contract
 *
 * This helper does not create or replace a global Micrometer registry. It reads
 * meters from an existing [MeterRegistry] only when application code invokes
 * one of the publish methods.
 */
interface CloudWatchMeterPublishingOperations {

    /**
     * Publishes snapshots from all meters accepted by [predicate].
     *
     * ## Contract
     *
     * The helper sends the current in-memory Micrometer measurements once. It
     * does not register a scheduled publisher or mutate the source registry.
     * A Micrometer meter with more than 30 tags is rejected before CloudWatch is
     * called because CloudWatch allows at most 30 dimensions per metric datum.
     */
    suspend fun publishMeters(predicate: (Meter) -> Boolean = { true }): List<PutMetricDataResponse>

    /**
     * Publishes snapshots for meters whose Micrometer name equals [name].
     */
    suspend fun publishMeter(name: String): List<PutMetricDataResponse>
}

/**
 * Default [CloudWatchMeterPublishingOperations] implementation.
 */
class CloudWatchMeterPublishingTemplate(
    private val meterRegistry: MeterRegistry,
    private val cloudWatchOperations: CloudWatchOperations,
): CloudWatchMeterPublishingOperations {

    override suspend fun publishMeters(predicate: (Meter) -> Boolean): List<PutMetricDataResponse> {
        val metricData = meterRegistry.meters
            .asSequence()
            .filter(predicate)
            .flatMap { it.toMetricData().asSequence() }
            .toList()
        if (metricData.isEmpty()) {
            return emptyList()
        }

        return cloudWatchOperations.putMetricData(metricData)
    }

    override suspend fun publishMeter(name: String): List<PutMetricDataResponse> {
        name.requireNotBlank("name")
        return publishMeters { it.id.name == name }
    }

    private fun Meter.toMetricData(): List<MetricDatum> {
        val dimensions = toCloudWatchDimensions()

        return measure()
            .filter { it.value.isFinite() }
            .map { measurement -> measurement.toMetricDatum(id.name, dimensions) }
    }

    private fun Meter.toCloudWatchDimensions(): List<Dimension> =
        id.tags
            .map { tag ->
                Dimension.builder()
                    .name(tag.key)
                    .value(tag.value)
                    .build()
            }
            .also { dimensions ->
                dimensions.size.requireInRange(0, CLOUDWATCH_MAX_DIMENSIONS, "CloudWatch metric dimensions")
            }

    private fun Measurement.toMetricDatum(
        meterName: String,
        dimensions: List<Dimension>,
    ): MetricDatum =
        MetricDatum.builder()
            .metricName("${meterName}.${statistic.name.lowercase(Locale.US)}")
            .dimensions(dimensions)
            .unit(statistic.toStandardUnit())
            .value(value)
            .build()

    private fun Statistic.toStandardUnit(): StandardUnit =
        when (this) {
            Statistic.COUNT -> StandardUnit.COUNT
            else -> StandardUnit.NONE
        }
}
