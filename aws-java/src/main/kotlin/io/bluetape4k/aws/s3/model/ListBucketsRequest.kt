package io.bluetape4k.aws.s3.model

import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.services.s3.model.ListBucketsRequest

/**
 * See the API documentation for details.
 *
 * Example:
 * ```kotlin
 * val result = listBucketsRequest { maxBuckets(50) }
 * // result.maxBuckets() == 50
 * ```
 */
inline fun listBucketsRequest(
    builder: ListBucketsRequest.Builder.() -> Unit = {},
): ListBucketsRequest =
    ListBucketsRequest.builder().apply(builder).build()

/**
 * See the API documentation for details.
 *
 * Example:
 * ```kotlin
 * val result = listBucketsRequestOf {
 *     apiCallAttemptTimeout(java.time.Duration.ofSeconds(3))
 * }
 * // result.overrideConfiguration().isPresent == true
 * ```
 */
fun listBucketsRequestOf(
    configrationBuilder: AwsRequestOverrideConfiguration.Builder.() -> Unit = {},
): ListBucketsRequest =
    listBucketsRequest {
        overrideConfiguration(configrationBuilder)
    }
