package io.bluetape4k.aws.ktor.cloudwatch

import software.amazon.awssdk.services.cloudwatch.model.DimensionFilter
import software.amazon.awssdk.services.cloudwatch.model.ListMetricsResponse
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse

/**
 * Ktor 애플리케이션을 위한 코루틴 CloudWatch 메트릭 작업입니다.
 *
 * ## 계약
 *
 * 작업을 호출할 때만 AWS를 호출합니다. 기본 네임스페이스 메서드는 설치된
 * [CloudWatchKtorPlugin]에 구성한 네임스페이스가 필요합니다.
 */
interface CloudWatchKtorOperations {

    /**
     * [metricData]를 구성된 기본 네임스페이스에 게시합니다.
     */
    suspend fun putMetricData(metricData: List<MetricDatum>): List<PutMetricDataResponse>

    /**
     * [metricData]를 [namespace]에 게시합니다.
     */
    suspend fun putMetricData(
        namespace: String,
        metricData: List<MetricDatum>,
    ): List<PutMetricDataResponse>

    /**
     * [metricDatum] 하나를 구성된 기본 네임스페이스에 게시합니다.
     */
    suspend fun putMetricDatum(metricDatum: MetricDatum): List<PutMetricDataResponse> =
        putMetricData(listOf(metricDatum))

    /**
     * [metricDatum] 하나를 [namespace]에 게시합니다.
     */
    suspend fun putMetricDatum(
        namespace: String,
        metricDatum: MetricDatum,
    ): List<PutMetricDataResponse> =
        putMetricData(namespace, listOf(metricDatum))

    /**
     * CloudWatch 메트릭 목록을 조회합니다.
     */
    suspend fun listMetrics(
        namespace: String? = null,
        metricName: String? = null,
        dimensions: List<DimensionFilter>? = null,
    ): ListMetricsResponse
}
