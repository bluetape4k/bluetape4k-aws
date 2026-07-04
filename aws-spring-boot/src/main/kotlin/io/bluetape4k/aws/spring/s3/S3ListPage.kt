package io.bluetape4k.aws.spring.s3

import software.amazon.awssdk.services.s3.model.S3Object
import java.io.Serializable

/**
 * One page of S3 `ListObjectsV2` results.
 */
data class S3ListPage(
    val objects: List<S3Object>,
    val isTruncated: Boolean,
    val nextContinuationToken: String?,
    val keyCount: Int,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
