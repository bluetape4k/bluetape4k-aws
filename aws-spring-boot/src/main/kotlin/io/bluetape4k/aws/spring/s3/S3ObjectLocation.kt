package io.bluetape4k.aws.spring.s3

import java.io.Serializable

/**
 * S3 object location.
 */
data class S3ObjectLocation(
    val bucket: String,
    val key: String,
): Serializable {
    init {
        require(bucket.isNotBlank()) { "bucket must not be blank." }
        require(key.isNotBlank()) { "key must not be blank." }
    }

    override fun toString(): String = "s3://$bucket/$key"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
