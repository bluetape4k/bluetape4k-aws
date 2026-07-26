package io.bluetape4k.aws.cloudwatch

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.cloudwatch.model.DimensionFilter
import software.amazon.awssdk.services.cloudwatch.model.ListMetricsResponse
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse
import java.util.concurrent.CompletableFuture

/**
 * Publishes [metricData] to CloudWatch under [namespace] asynchronously.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [namespace] is blank.
 *
 * ```kotlin
 * val response = cloudWatchAsyncClient.putMetricDataAsync(
 *     namespace = "MyApp/Performance",
 *     metricData = listOf(metricDatum)
 * ).join()
 * ```
 */
fun CloudWatchAsyncClient.putMetricDataAsync(
    namespace: String,
    metricData: List<MetricDatum>,
): CompletableFuture<PutMetricDataResponse> {
    namespace.requireNotBlank("namespace")
    return putMetricData {
        it.namespace(namespace)
        it.metricData(metricData)
    }
}

/**
 * Publishes a single [metricDatum] to CloudWatch under [namespace] asynchronously.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [namespace] is blank.
 *
 * ```kotlin
 * val response = cloudWatchAsyncClient.putMetricDataAsync(
 *     namespace = "MyApp/Performance",
 *     metricDatum = MetricDatum.builder().metricName("Latency").value(100.0).build()
 * ).join()
 * ```
 */
fun CloudWatchAsyncClient.putMetricDataAsync(
    namespace: String,
    metricDatum: MetricDatum,
): CompletableFuture<PutMetricDataResponse> {
    namespace.requireNotBlank("namespace")
    return putMetricDataAsync(namespace, listOf(metricDatum))
}

/**
 * Lists metrics in [namespace] asynchronously.
 *
 * ```kotlin
 * val response = cloudWatchAsyncClient.listMetricsAsync(namespace = "MyApp/Performance").join()
 * response.metrics().forEach { metric -> println(metric.metricName()) }
 * ```
 */
fun CloudWatchAsyncClient.listMetricsAsync(
    namespace: String? = null,
    metricName: String? = null,
    dimensions: List<DimensionFilter>? = null,
): CompletableFuture<ListMetricsResponse> =
    listMetrics {
        namespace?.let { ns -> it.namespace(ns) }
        metricName?.let { mn -> it.metricName(mn) }
        dimensions?.let { dims -> it.dimensions(dims) }
    }
