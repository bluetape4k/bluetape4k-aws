package io.bluetape4k.aws.spring.cloudwatch

import software.amazon.awssdk.services.cloudwatch.model.DimensionFilter
import software.amazon.awssdk.services.cloudwatch.model.ListMetricsResponse
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse

/**
 * Spring 애플리케이션을 위한 코루틴 기반 CloudWatch 메트릭 작업입니다.
 *
 * ## 계약
 *
 * 애플리케이션 코드에 `CompletableFuture`를 노출하지 않고 명시적인 사용자 정의 메트릭 게시를
 * 제공합니다. 기본 네임스페이스 메서드에는 `bluetape4k.aws.cloudwatch.namespace`가 필요합니다.
 */
interface CloudWatchOperations {

    /**
     * 메트릭 데이터를 구성된 기본 네임스페이스에 게시합니다.
     */
    suspend fun putMetricData(metricData: List<MetricDatum>): List<PutMetricDataResponse>

    /**
     * 메트릭 데이터를 명시적인 네임스페이스에 게시합니다.
     */
    suspend fun putMetricData(
        namespace: String,
        metricData: List<MetricDatum>,
    ): List<PutMetricDataResponse>

    /**
     * 메트릭 데이터 하나를 구성된 기본 네임스페이스에 게시합니다.
     */
    suspend fun putMetricDatum(metricDatum: MetricDatum): PutMetricDataResponse

    /**
     * 메트릭 데이터 하나를 명시적인 네임스페이스에 게시합니다.
     */
    suspend fun putMetricDatum(
        namespace: String,
        metricDatum: MetricDatum,
    ): PutMetricDataResponse

    /**
     * CloudWatch 메트릭 목록을 조회합니다.
     */
    suspend fun listMetrics(
        namespace: String? = null,
        metricName: String? = null,
        dimensions: List<DimensionFilter>? = null,
    ): ListMetricsResponse
}
