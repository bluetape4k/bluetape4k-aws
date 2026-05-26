package io.bluetape4k.aws.examples.ktor.s3

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.coroutines.runSuspendIO
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Test

class S3KtorExamplesTest {

    @Test
    fun `LocalStack client example can create presigned download URL`() {
        val s3 = S3KtorExamples.localStackClient(
            signingClock = Clock.fixed(Instant.parse("2026-05-10T01:02:03Z"), ZoneOffset.UTC)
        )

        try {
            val presigned = S3KtorExamples.presignDownload(
                s3 = s3,
                bucket = "demo-bucket",
                key = "logs/2026/app.log",
                expires = Duration.ofMinutes(15),
            )

            presigned.method shouldBeEqualTo "GET"
            presigned.url.host shouldBeEqualTo "localhost"
            presigned.url.encodedPath shouldBeEqualTo "/demo-bucket/logs/2026/app.log"
            presigned.url.parameters["X-Amz-Algorithm"] shouldBeEqualTo "AWS4-HMAC-SHA256"
            presigned.url.parameters["X-Amz-Expires"] shouldBeEqualTo "900"
        } finally {
            s3.close()
        }
    }

    @Test
    fun `in-memory data key provider decrypts generated demo key`() = runSuspendIO {
        val provider = InMemoryS3DataKeyProvider()

        val dataKey = provider.generateDataKey(mapOf("tenant" to "demo"))
        val decrypted = provider.decryptDataKey(dataKey.encryptedKey, mapOf("tenant" to "demo"))

        decrypted shouldBeEqualTo dataKey.plaintextKey
    }
}
