package io.bluetape4k.aws.spring.s3

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.nio.file.Files

class S3ObjectContentTypeResolverTest {

    private val resolver = DefaultS3ObjectContentTypeResolver()

    @Test
    fun `explicit content type wins over metadata and file detection`() {
        resolver.resolve(
            key = "report.json",
            overrideContentType = "application/custom",
            metadata = mapOf("Content-Type" to "text/plain"),
        ) shouldBeEqualTo "application/custom"
    }

    @Test
    fun `metadata content type wins over file detection`() {
        resolver.resolve(
            key = "report.json",
            metadata = mapOf("content-type" to "application/vnd.api+json"),
        ) shouldBeEqualTo "application/vnd.api+json"
    }

    @Test
    fun `file name is used before binary fallback`() {
        resolver.resolve("report.json") shouldBeEqualTo "application/json"
        resolver.resolve("archive.unknown-bluetape-extension") shouldBeEqualTo "application/octet-stream"
    }

    @Test
    fun `resolver can use a supplied probe without touching the filesystem`() {
        val custom = DefaultS3ObjectContentTypeResolver { "image/avif" }
        custom.resolve("asset.bin") shouldBeEqualTo "image/avif"
        Files.createTempFile("bluetape-content-type", ".tmp").toFile().deleteOnExit()
    }
}
