package io.bluetape4k.aws.spring.s3

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * S3 객체 위치.
 */
data class S3ObjectLocation(
    val bucket: String,
    val key: String,
): Serializable {
    init {
        bucket.requireNotBlank("bucket")
        key.requireNotBlank("key")
    }

    override fun toString(): String = "s3://$bucket/$key"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
