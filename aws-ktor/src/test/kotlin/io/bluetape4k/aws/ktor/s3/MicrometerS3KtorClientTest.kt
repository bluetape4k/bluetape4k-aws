package io.bluetape4k.aws.ktor.s3

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.aws.ktor.observability.KtorMetricContract
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.support.toUtf8Bytes
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MicrometerS3KtorClientTest {

    companion object: KLoggingChannel()

    private val delegate = mockk<S3KtorClient>()

    @BeforeEach
    fun beforeEach() {
        clearMocks(delegate)
    }

    @Test
    fun `record selected S3 Ktor operation timer`() = runSuspendIO {
        val registry = SimpleMeterRegistry()

        coEvery {
            delegate.getObjectBytes("documents", "hello.txt")
        } returns "hello".toUtf8Bytes()

        val client = delegate.withMicrometer(registry, includeBucketTag = true)

        client.getObjectBytes("documents", "hello.txt")

        val timer = registry.find(MicrometerS3KtorClient.DEFAULT_METER_NAME)
            .tag(KtorMetricContract.TAG_OPERATION, KtorMetricContract.OPERATION_GET_OBJECT)
            .tag(KtorMetricContract.TAG_OUTCOME, KtorMetricContract.OUTCOME_SUCCESS)
            .tag(KtorMetricContract.TAG_BUCKET, "documents")
            .timer()

        timer.shouldNotBeNull()
        timer.count() shouldBeEqualTo 1L
    }
}
