package io.bluetape4k.aws.kotlin.cloudwatch

import aws.sdk.kotlin.services.cloudwatch.CloudWatchClient
import aws.sdk.kotlin.services.cloudwatch.listMetrics
import aws.sdk.kotlin.services.cloudwatch.model.DimensionFilter
import aws.sdk.kotlin.services.cloudwatch.model.ListMetricsRequest
import aws.sdk.kotlin.services.cloudwatch.model.ListMetricsResponse
import aws.sdk.kotlin.services.cloudwatch.model.MetricDatum
import aws.sdk.kotlin.services.cloudwatch.model.PutMetricDataRequest
import aws.sdk.kotlin.services.cloudwatch.model.PutMetricDataResponse
import aws.sdk.kotlin.services.cloudwatch.putMetricData
import io.bluetape4k.support.requireNotBlank

/**
 * Publishes [metricData] to CloudWatch under [namespace].
 *
 * ```kotlin
 * val response = cloudWatchClient.putMetricData(
 *     namespace = "MyApp/Performance",
 *     metricData = listOf(metricDatum)
 * )
 * ```
 *
 * @param namespace CloudWatch namespace
 * @param metricData metric data to publish
 * @param builder additional configuration for [PutMetricDataRequest.Builder]
 * @return the [PutMetricDataResponse]
 */
suspend inline fun CloudWatchClient.putMetricData(
    namespace: String,
    metricData: List<MetricDatum>,
    crossinline builder: PutMetricDataRequest.Builder.() -> Unit = {},
): PutMetricDataResponse {
    namespace.requireNotBlank("namespace")
    return putMetricData {
        this.namespace = namespace
        this.metricData = metricData
        builder()
    }
}

/**
 * Publishes a single [metricDatum] to CloudWatch under [namespace].
 *
 * ```kotlin
 * val response = cloudWatchClient.putMetricData(
 *     namespace = "MyApp/Performance",
 *     metricDatum = MetricDatum { metricName = "Latency"; value = 100.0 }
 * )
 * ```
 *
 * @param namespace CloudWatch namespace
 * @param metricDatum metric datum to publish
 * @param builder additional configuration for [PutMetricDataRequest.Builder]
 * @return the [PutMetricDataResponse]
 */
suspend inline fun CloudWatchClient.putMetricData(
    namespace: String,
    metricDatum: MetricDatum,
    crossinline builder: PutMetricDataRequest.Builder.() -> Unit = {},
): PutMetricDataResponse {
    namespace.requireNotBlank("namespace")
    return putMetricData(namespace, listOf(metricDatum), builder)
}

/**
 * Lists CloudWatch metrics using namespace and filter criteria.
 *
 * ```kotlin
 * val response = cloudWatchClient.listMetrics(namespace = "MyApp/Performance")
 * response.metrics?.forEach { metric -> println(metric.metricName) }
 * ```
 *
 * @param namespace namespace to query; when null, queries all namespaces
 * @param metricName metric name to query; when null, does not filter by name
 * @param dimensions dimension filters; when null, does not filter by dimensions
 * @param builder additional configuration for [ListMetricsRequest.Builder]
 * @return the [ListMetricsResponse]
 */
suspend inline fun CloudWatchClient.listMetrics(
    namespace: String? = null,
    metricName: String? = null,
    dimensions: List<DimensionFilter>? = null,
    crossinline builder: ListMetricsRequest.Builder.() -> Unit = {},
): ListMetricsResponse =
    listMetrics {
        namespace?.let { this.namespace = it }
        metricName?.let { this.metricName = it }
        dimensions?.let { this.dimensions = it }
        builder()
    }
