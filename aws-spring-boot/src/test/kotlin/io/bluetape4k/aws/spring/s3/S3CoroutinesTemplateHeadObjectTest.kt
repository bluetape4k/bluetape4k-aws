package io.bluetape4k.aws.spring.s3

import io.bluetape4k.assertions.shouldBeEqualTo
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.time.Instant
import java.util.concurrent.CompletableFuture

class S3CoroutinesTemplateHeadObjectTest {

    private val asyncClient = mockk<S3AsyncClient>()
    private val template = S3CoroutinesTemplate(
        s3AsyncClient = asyncClient,
        s3Client = mockk<S3Client>(relaxed = true),
        s3Presigner = mockk<S3Presigner>(relaxed = true),
        properties = S3Properties(),
    )

    @BeforeEach
    fun resetMocks() {
        clearMocks(asyncClient)
    }

    @Test
    fun headObjectSendsOneRequestAndPreservesResponseMetadata() = runTest {
        val lastModified = Instant.parse("2026-08-15T00:00:01.123Z")
        val response = HeadObjectResponse.builder()
            .contentLength(42)
            .eTag("\"etag-token\"")
            .contentType("image/png")
            .lastModified(lastModified)
            .build()
        val requestSlot = slot<HeadObjectRequest>()
        every { asyncClient.headObject(any<HeadObjectRequest>()) } returns
            CompletableFuture.completedFuture(response)

        val metadata = template.headObject("images", "sample.png")

        metadata shouldBeEqualTo S3ObjectMetadata(
            sizeBytes = 42,
            etag = "\"etag-token\"",
            contentType = "image/png",
            lastModified = lastModified,
        )
        verify(exactly = 1) { asyncClient.headObject(capture(requestSlot)) }
        requestSlot.captured.bucket() shouldBeEqualTo "images"
        requestSlot.captured.key() shouldBeEqualTo "sample.png"
    }
}
