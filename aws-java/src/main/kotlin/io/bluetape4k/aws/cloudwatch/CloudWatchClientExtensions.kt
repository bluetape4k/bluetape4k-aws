package io.bluetape4k.aws.cloudwatch

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient
import software.amazon.awssdk.services.cloudwatch.model.DimensionFilter
import software.amazon.awssdk.services.cloudwatch.model.ListMetricsResponse
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse

/**
 * Publishes [metricData] to CloudWatch under [namespace].
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [namespace] is blank.
 * - Does nothing when [metricData] is empty.
 *
 * ```kotlin
 * val response = cloudWatchClient.putMetricData(
 *     namespace = "MyApp/Performance",
 *     metricData = listOf(metricDatum)
 * )
 * response.sdkHttpResponse().statusCode() == 200
 * ```
 */
fun CloudWatchClient.putMetricData(
    namespace: String,
    metricData: List<MetricDatum>,
): PutMetricDataResponse {
    namespace.requireNotBlank("namespace")
    return putMetricData {
        it.namespace(namespace)
        it.metricData(metricData)
    }
}

/**
 * Publishes a single [metricDatum] to CloudWatch under [namespace].
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [namespace] is blank.
 *
 * ```kotlin
 * val response = cloudWatchClient.putMetricData(
 *     namespace = "MyApp/Performance",
 *     metricDatum = MetricDatum.builder().metricName("Latency").value(100.0).build()
 * )
 * ```
 */
fun CloudWatchClient.putMetricData(
    namespace: String,
    metricDatum: MetricDatum,
): PutMetricDataResponse {
    namespace.requireNotBlank("namespace")
    return putMetricData(namespace, listOf(metricDatum))
}

/**
 * Lists metrics in [namespace].
 *
 * ## Behavior/Contract
 * - When [namespace] is non-null, lists only metrics in that namespace.
 * - When [metricName] is non-null, lists only metrics with that name.
 * - When [dimensions] is non-null, filters by those dimensions.
 *
 * ```kotlin
 * val response = cloudWatchClient.listMetrics(namespace = "MyApp/Performance")
 * response.metrics().forEach { metric -> println(metric.metricName()) }
 * ```
 */
fun CloudWatchClient.listMetrics(
    namespace: String? = null,
    metricName: String? = null,
    dimensions: List<DimensionFilter>? = null,
): ListMetricsResponse =
    listMetrics {
        namespace?.let { ns -> it.namespace(ns) }
        metricName?.let { mn -> it.metricName(mn) }
        dimensions?.let { dims -> it.dimensions(dims) }
    }
