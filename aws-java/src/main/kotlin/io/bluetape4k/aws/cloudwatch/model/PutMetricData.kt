package io.bluetape4k.aws.cloudwatch.model

import software.amazon.awssdk.services.cloudwatch.model.MetricDatum
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit

/**
 * Builds a [PutMetricDataRequest] with a DSL block.
 *
 * ```kotlin
 * val request = putMetricDataRequest {
 *     namespace("MyApp/Performance")
 *     metricData(listOf(metricDatum))
 * }
 * ```
 */
inline fun putMetricDataRequest(
    builder: PutMetricDataRequest.Builder.() -> Unit,
): PutMetricDataRequest =
    PutMetricDataRequest.builder().apply(builder).build()

/**
 * Creates a [PutMetricDataRequest] from a namespace and metric data list.
 *
 * ```kotlin
 * val request = putMetricDataRequestOf(
 *     namespace = "MyApp/Performance",
 *     metricData = listOf(metricDatum)
 * )
 * ```
 */
inline fun putMetricDataRequestOf(
    namespace: String,
    metricData: List<MetricDatum>,
    builder: PutMetricDataRequest.Builder.() -> Unit = {},
): PutMetricDataRequest = putMetricDataRequest {
    namespace(namespace)
    metricData(metricData)
    builder()
}

/**
 * Builds a [MetricDatum] with a DSL block.
 *
 * ```kotlin
 * val datum = metricDatum {
 *     metricName("Latency")
 *     value(100.0)
 *     unit(StandardUnit.MILLISECONDS)
 * }
 * ```
 */
inline fun metricDatum(
    builder: MetricDatum.Builder.() -> Unit,
): MetricDatum =
    MetricDatum.builder().apply(builder).build()

/**
 * Creates a [MetricDatum] from a metric name and value.
 *
 * ```kotlin
 * val datum = metricDatumOf(
 *     metricName = "Latency",
 *     value = 100.0,
 *     unit = StandardUnit.MILLISECONDS
 * )
 * ```
 */
inline fun metricDatumOf(
    metricName: String,
    value: Double,
    unit: StandardUnit = StandardUnit.NONE,
    builder: MetricDatum.Builder.() -> Unit = {},
): MetricDatum = metricDatum {
    metricName(metricName)
    value(value)
    unit(unit)
    builder()
}
