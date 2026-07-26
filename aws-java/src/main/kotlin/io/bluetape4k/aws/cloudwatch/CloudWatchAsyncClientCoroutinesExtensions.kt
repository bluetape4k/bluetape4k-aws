package io.bluetape4k.aws.cloudwatch

import kotlinx.coroutines.future.await
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.cloudwatch.model.DimensionFilter
import software.amazon.awssdk.services.cloudwatch.model.ListMetricsResponse
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse

/**
 * Publishes [metricData] to CloudWatch under [namespace] with coroutines.
 *
 * ## Behavior/Contract
 * - Internally calls [putMetricDataAsync] and waits for completion with `await()`.
 *
 * ```kotlin
 * val response = cloudWatchAsyncClient.putMetricData(
 *     namespace = "MyApp/Performance",
 *     metricData = listOf(metricDatum)
 * )
 * ```
 */
suspend fun CloudWatchAsyncClient.putMetricData(
    namespace: String,
    metricData: List<MetricDatum>,
): PutMetricDataResponse =
    putMetricDataAsync(namespace, metricData).await()

/**
 * Publishes a single [metricDatum] to CloudWatch under [namespace] with coroutines.
 *
 * ## Behavior/Contract
 * - Internally calls [putMetricDataAsync] and waits for completion with `await()`.
 *
 * ```kotlin
 * val response = cloudWatchAsyncClient.putMetricData(
 *     namespace = "MyApp/Performance",
 *     metricDatum = MetricDatum.builder().metricName("Latency").value(100.0).build()
 * )
 * ```
 */
suspend fun CloudWatchAsyncClient.putMetricData(
    namespace: String,
    metricDatum: MetricDatum,
): PutMetricDataResponse =
    putMetricDataAsync(namespace, metricDatum).await()

/**
 * Lists metrics in [namespace] with coroutines.
 *
 * ## Behavior/Contract
 * - Internally calls [listMetricsAsync] and waits for completion with `await()`.
 *
 * ```kotlin
 * val response = cloudWatchAsyncClient.listMetrics(namespace = "MyApp/Performance")
 * response.metrics().forEach { metric -> println(metric.metricName()) }
 * ```
 */
suspend fun CloudWatchAsyncClient.listMetrics(
    namespace: String? = null,
    metricName: String? = null,
    dimensions: List<DimensionFilter>? = null,
): ListMetricsResponse =
    listMetricsAsync(namespace, metricName, dimensions).await()
