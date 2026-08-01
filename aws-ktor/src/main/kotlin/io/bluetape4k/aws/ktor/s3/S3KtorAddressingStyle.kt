package io.bluetape4k.aws.ktor.s3

/**
 * S3 REST 요청의 bucket 주소 지정 방식입니다.
 *
 * ## 동작/계약
 *
 * [VirtualHosted]는 AWS S3 기본 endpoint에서 DNS-safe bucket을 host에 포함하고,
 * [Path]는 LocalStack이나 S3 호환 endpoint처럼 bucket을 path 첫 segment로 보내야 할 때 사용합니다.
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
     * Bucket 이름을 host에 포함합니다.
     *
     * ```text
     * https://bucket.s3.ap-northeast-2.amazonaws.com/key
     * ```
     */
    VirtualHosted,

    /**
     * Bucket 이름을 path 첫 segment로 둡니다.
     *
     * ```text
     * https://s3.ap-northeast-2.amazonaws.com/bucket/key
     * ```
     */
    Path,
}
