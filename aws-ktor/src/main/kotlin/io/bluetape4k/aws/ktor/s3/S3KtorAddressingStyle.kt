package io.bluetape4k.aws.ktor.s3

/**
 * S3 REST 요청의 bucket 주소 지정 방식입니다.
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
