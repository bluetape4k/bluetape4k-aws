package io.bluetape4k.aws.spring.s3

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeFalse
import io.mockk.every
import io.mockk.mockk
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import org.junit.jupiter.api.Test

class S3ResourceTest {

    private val client = mockk<S3Client>()
    private val resource = S3Resource(client, S3ObjectLocation("bucket", "config/application.yml"))

    @Test
    fun `missing object remains absent for HTTP 404`() {
        every { client.headObject(any<HeadObjectRequest>()) } throws S3Exception.builder().statusCode(404).build()

        resource.exists().shouldBeFalse()
    }

    @Test
    fun `permission error is not mistaken for missing object`() {
        every { client.headObject(any<HeadObjectRequest>()) } throws S3Exception.builder().statusCode(403).build()

        assertFailsWith<S3Exception> { resource.exists() }
    }
}
