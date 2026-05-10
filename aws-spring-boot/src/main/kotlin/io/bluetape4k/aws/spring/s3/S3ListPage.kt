package io.bluetape4k.aws.spring.s3

import software.amazon.awssdk.services.s3.model.S3Object

/**
 * S3 `ListObjectsV2` 한 페이지 결과.
 */
data class S3ListPage(
    val objects: List<S3Object>,
    val isTruncated: Boolean,
    val nextContinuationToken: String?,
    val keyCount: Int,
)
