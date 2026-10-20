package io.bluetape4k.aws.ktor.cloudwatch

import aws.sdk.kotlin.services.cloudwatch.CloudWatchClient
import aws.sdk.kotlin.services.cloudwatch.model.DimensionFilter
import aws.sdk.kotlin.services.cloudwatch.model.ListMetricsResponse
import aws.sdk.kotlin.services.cloudwatch.model.MetricDatum
import aws.sdk.kotlin.services.cloudwatch.model.PutMetricDataResponse
import io.bluetape4k.aws.kotlin.cloudwatch.listMetrics
import io.bluetape4k.aws.kotlin.cloudwatch.putMetricData
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank

internal const val CLOUDWATCH_MIN_BATCH_SIZE = 1
internal const val CLOUDWATCH_MAX_BATCH_SIZE = 1000

/**
 * AWS Kotlin SDK [CloudWatchClient]를 사용하는 기본 [CloudWatchKtorOperations] 구현입니다.
 *
 * ## 계약
 *
 * 메트릭 데이터는 [batchSize] 단위로 나눕니다. 빈 메트릭 목록은 아무 작업도 하지 않으며
 * AWS를 호출하지 않습니다.
 */
class CloudWatchKtorTemplate(
    private val cloudWatchClient: CloudWatchClient,
    private val namespace: String? = null,
    private val batchSize: Int = CLOUDWATCH_MAX_BATCH_SIZE,
): CloudWatchKtorOperations {

    companion object: KLoggingChannel()

    init {
        batchSize.requireInRange(CLOUDWATCH_MIN_BATCH_SIZE, CLOUDWATCH_MAX_BATCH_SIZE, "batchSize")
    }

    override suspend fun putMetricData(metricData: List<MetricDatum>): List<PutMetricDataResponse> =
        putMetricData(defaultNamespace(), metricData)

    override suspend fun putMetricData(
        namespace: String,
        metricData: List<MetricDatum>,
    ): List<PutMetricDataResponse> {
        namespace.requireNotBlank("namespace")
        if (metricData.isEmpty()) {
            return emptyList()
        }

        return metricData.chunked(batchSize).map { batch ->
            cloudWatchClient.putMetricData(namespace, batch)
        }
    }

    override suspend fun listMetrics(
        namespace: String?,
        metricName: String?,
        dimensions: List<DimensionFilter>?,
    ): ListMetricsResponse =
        cloudWatchClient.listMetrics(namespace, metricName, dimensions)

    private fun defaultNamespace(): String =
        namespace?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("namespace must be configured.")
}
