package io.bluetape4k.aws.ktor.cloudwatch

import software.amazon.awssdk.services.cloudwatch.model.DimensionFilter
import software.amazon.awssdk.services.cloudwatch.model.ListMetricsResponse
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse

/**
 * Coroutine CloudWatch metric operations for Ktor applications.
 *
 * ## Contract
 *
 * Operations call AWS only when invoked. Default-namespace methods require the
 * namespace configured for the installed [CloudWatchKtorPlugin].
 */
interface CloudWatchKtorOperations {

    /**
     * Publishes [metricData] to the configured default namespace.
     */
    suspend fun putMetricData(metricData: List<MetricDatum>): List<PutMetricDataResponse>

    /**
     * Publishes [metricData] to [namespace].
     */
    suspend fun putMetricData(
        namespace: String,
        metricData: List<MetricDatum>,
    ): List<PutMetricDataResponse>

    /**
     * Publishes one [metricDatum] to the configured default namespace.
     */
    suspend fun putMetricDatum(metricDatum: MetricDatum): List<PutMetricDataResponse> =
        putMetricData(listOf(metricDatum))

    /**
     * Publishes one [metricDatum] to [namespace].
     */
    suspend fun putMetricDatum(
        namespace: String,
        metricDatum: MetricDatum,
    ): List<PutMetricDataResponse> =
        putMetricData(namespace, listOf(metricDatum))

    /**
     * Lists CloudWatch metrics.
     */
    suspend fun listMetrics(
        namespace: String? = null,
        metricName: String? = null,
        dimensions: List<DimensionFilter>? = null,
    ): ListMetricsResponse
}
