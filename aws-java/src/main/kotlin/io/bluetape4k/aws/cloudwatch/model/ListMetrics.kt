package io.bluetape4k.aws.cloudwatch.model

import software.amazon.awssdk.services.cloudwatch.model.DimensionFilter
import software.amazon.awssdk.services.cloudwatch.model.ListMetricsRequest

/**
 * Builds a [ListMetricsRequest] with a DSL block.
 *
 * ```kotlin
 * val request = listMetricsRequest {
 *     namespace("MyApp/Performance")
 * }
 * ```
 */
inline fun listMetricsRequest(
    builder: ListMetricsRequest.Builder.() -> Unit,
): ListMetricsRequest =
    ListMetricsRequest.builder().apply(builder).build()

/**
 * Creates a [ListMetricsRequest] from a namespace and optional filters.
 *
 * ```kotlin
 * val request = listMetricsRequestOf(
 *     namespace = "MyApp/Performance",
 *     metricName = "Latency"
 * )
 * ```
 */
inline fun listMetricsRequestOf(
    namespace: String? = null,
    metricName: String? = null,
    dimensions: List<DimensionFilter>? = null,
    builder: ListMetricsRequest.Builder.() -> Unit = {},
): ListMetricsRequest = listMetricsRequest {
    namespace?.let { namespace(it) }
    metricName?.let { metricName(it) }
    dimensions?.let { dimensions(it) }
    builder()
}
