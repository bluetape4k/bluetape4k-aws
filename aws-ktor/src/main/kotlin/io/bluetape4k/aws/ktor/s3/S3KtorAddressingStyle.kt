package io.bluetape4k.aws.ktor.s3

/**
 * Bucket addressing style for S3 REST requests.
 *
 * ## Behavior/Contract
 *
 * [VirtualHosted] puts a DNS-safe bucket in the host for the default AWS S3 endpoint, while
 * [Path] sends the bucket as the first path segment for LocalStack or S3-compatible endpoints.
 *
 * ```kotlin
 * val s3 = s3KtorClientOf(
 *     region = "ap-northeast-2",
 *     addressingStyle = S3KtorAddressingStyle.Path,
 * )
 * ```
 */
enum class S3KtorAddressingStyle {
    /**
     * Includes the bucket name in the host.
     *
     * ```text
     * https://bucket.s3.ap-northeast-2.amazonaws.com/key
     * ```
     */
    VirtualHosted,

    /**
     * Places the bucket name in the first path segment.
     *
     * ```text
     * https://s3.ap-northeast-2.amazonaws.com/bucket/key
     * ```
     */
    Path,
}
