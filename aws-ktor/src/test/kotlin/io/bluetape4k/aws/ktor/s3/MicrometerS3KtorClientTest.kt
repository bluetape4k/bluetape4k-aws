package io.bluetape4k.aws.ktor.s3

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MicrometerS3KtorClientTest {

    @Test
    fun `record selected S3 Ktor operation timer`() = runSuspendIO {
        val registry = SimpleMeterRegistry()
        val delegate = mockk<S3KtorClient>()
        coEvery { delegate.getObjectBytes("documents", "hello.txt") } returns "hello".encodeToByteArray()
        val client = delegate.withMicrometer(registry, includeBucketTag = true)

        client.getObjectBytes("documents", "hello.txt")

        val timer = registry.find(MicrometerS3KtorClient.DEFAULT_METER_NAME)
            .tag("operation", "get_object")
            .tag("outcome", "success")
            .tag("bucket", "documents")
            .timer()
        timer.shouldNotBeNull()
        timer.count() shouldBeEqualTo 1L
    }
}
