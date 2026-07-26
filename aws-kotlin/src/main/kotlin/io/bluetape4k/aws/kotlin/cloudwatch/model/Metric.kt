package io.bluetape4k.aws.kotlin.cloudwatch.model

import aws.sdk.kotlin.services.cloudwatch.model.MetricDatum
import aws.sdk.kotlin.services.cloudwatch.model.StandardUnit

/**
 * Builds a [MetricDatum] with a DSL block.
 *
 * ```kotlin
 * val datum = metricDatum {
 *     metricName = "Latency"
 *     value = 100.0
 *     unit = StandardUnit.Milliseconds
 * }
 * ```
 */
inline fun metricDatum(
    crossinline builder: MetricDatum.Builder.() -> Unit,
): MetricDatum =
    MetricDatum { builder() }

/**
 * Creates a [MetricDatum] from a metric name and value.
 *
 * ```kotlin
 * val datum = metricDatumOf(
 *     metricName = "Latency",
 *     value = 100.0,
 *     unit = StandardUnit.Milliseconds
 * )
 * ```
 *
 * @param metricName metric name
 * @param value metric value
 * @param unit metric unit; defaults to [StandardUnit.None]
 * @param builder additional configuration for [MetricDatum.Builder]
 * @return the [MetricDatum]
 */
inline fun metricDatumOf(
    metricName: String,
    value: Double,
    unit: StandardUnit = StandardUnit.None,
    crossinline builder: MetricDatum.Builder.() -> Unit = {},
): MetricDatum = metricDatum {
    this.metricName = metricName
    this.value = value
    this.unit = unit
    builder()
}
