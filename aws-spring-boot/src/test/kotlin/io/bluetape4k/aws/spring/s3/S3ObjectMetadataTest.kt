package io.bluetape4k.aws.spring.s3

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.time.Instant

class S3ObjectMetadataTest {

    @Test
    fun preservesNullableFieldsAndOpaqueETag() {
        val lastModified = Instant.parse("2026-08-15T00:00:01.123Z")

        val metadata = S3ObjectMetadata(
            sizeBytes = 42,
            etag = "\"multipart-token\"",
            contentType = null,
            lastModified = lastModified,
        )

        metadata.sizeBytes shouldBeEqualTo 42L
        metadata.etag shouldBeEqualTo "\"multipart-token\""
        metadata.contentType shouldBeEqualTo null
        metadata.lastModified shouldBeEqualTo lastModified
    }

    @Test
    fun rejectsNegativeObjectSize() {
        assertFailsWith<IllegalArgumentException> {
            S3ObjectMetadata(sizeBytes = -1)
        }
    }

    @Test
    fun legacyOperationsFailClosedWhenHeadIsUnsupported() {
        assertFailsWith<UnsupportedOperationException> {
            kotlinx.coroutines.test.runTest {
                NoopS3Operations.headObject("bucket", "key")
            }
        }
    }
}
