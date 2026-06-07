package io.bluetape4k.aws.spring.cloudwatch

import io.bluetape4k.aws.cloudwatch.listMetrics
import io.bluetape4k.aws.cloudwatch.putMetricData
import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.cloudwatch.model.DimensionFilter
import software.amazon.awssdk.services.cloudwatch.model.ListMetricsResponse
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse

/**
 * Coroutine-friendly [CloudWatchOperations] backed by AWS SDK v2 [CloudWatchAsyncClient].
 */
class CloudWatchCoroutinesTemplate(
    private val cloudWatchAsyncClient: CloudWatchAsyncClient,
    private val properties: CloudWatchProperties,
): CloudWatchOperations {

    override suspend fun putMetricData(metricData: List<MetricDatum>): List<PutMetricDataResponse> =
        putMetricData(requireDefaultNamespace(), metricData)

    override suspend fun putMetricData(
        namespace: String,
        metricData: List<MetricDatum>,
    ): List<PutMetricDataResponse> {
        namespace.requireNotBlank("namespace")
        if (metricData.isEmpty()) {
            return emptyList()
        }

        return metricData.chunked(properties.batchSize).map { batch ->
            cloudWatchAsyncClient.putMetricData(namespace, batch)
        }
    }

    override suspend fun putMetricDatum(metricDatum: MetricDatum): PutMetricDataResponse =
        putMetricDatum(requireDefaultNamespace(), metricDatum)

    override suspend fun putMetricDatum(
        namespace: String,
        metricDatum: MetricDatum,
    ): PutMetricDataResponse {
        namespace.requireNotBlank("namespace")
        return cloudWatchAsyncClient.putMetricData(namespace, metricDatum)
    }

    override suspend fun listMetrics(
        namespace: String?,
        metricName: String?,
        dimensions: List<DimensionFilter>?,
    ): ListMetricsResponse =
        cloudWatchAsyncClient.listMetrics(namespace, metricName, dimensions)

    private fun requireDefaultNamespace(): String =
        properties.namespace?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("$CLOUDWATCH_PROPERTIES_PREFIX.namespace is required.")
}
