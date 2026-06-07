package io.bluetape4k.aws.spring.cloudwatch

import software.amazon.awssdk.services.cloudwatch.model.DimensionFilter
import software.amazon.awssdk.services.cloudwatch.model.ListMetricsResponse
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse

/**
 * Coroutine-based CloudWatch metric operations for Spring applications.
 *
 * ## Contract
 *
 * Provides explicit custom metric publishing without exposing
 * `CompletableFuture` to application code. Default-namespace methods require
 * `bluetape4k.aws.cloudwatch.namespace`.
 */
interface CloudWatchOperations {

    /**
     * Publishes metric data to the configured default namespace.
     */
    suspend fun putMetricData(metricData: List<MetricDatum>): List<PutMetricDataResponse>

    /**
     * Publishes metric data to an explicit namespace.
     */
    suspend fun putMetricData(
        namespace: String,
        metricData: List<MetricDatum>,
    ): List<PutMetricDataResponse>

    /**
     * Publishes a single metric datum to the configured default namespace.
     */
    suspend fun putMetricDatum(metricDatum: MetricDatum): PutMetricDataResponse

    /**
     * Publishes a single metric datum to an explicit namespace.
     */
    suspend fun putMetricDatum(
        namespace: String,
        metricDatum: MetricDatum,
    ): PutMetricDataResponse

    /**
     * Lists CloudWatch metrics.
     */
    suspend fun listMetrics(
        namespace: String? = null,
        metricName: String? = null,
        dimensions: List<DimensionFilter>? = null,
    ): ListMetricsResponse
}
