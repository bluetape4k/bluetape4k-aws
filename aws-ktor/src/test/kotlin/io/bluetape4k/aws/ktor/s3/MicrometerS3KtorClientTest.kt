package io.bluetape4k.aws.ktor.s3

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.aws.ktor.observability.KtorMetricContract
import io.bluetape4k.javatimes.minutes
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.support.toUtf8Bytes
import io.ktor.http.Url
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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

    @Test
    fun `record presign operation timer`() = runSuspendIO {
        val registry = SimpleMeterRegistry()
        val expected = S3KtorPresignedRequest("GET", Url("https://s3.example.com/object"))
        coEvery {
            delegate.presignGetObject("documents", "hello.txt", 15.minutes())
        } returns expected

        val client = delegate.withMicrometer(registry, includeBucketTag = true)
        val presigned = client.presignGetObject("documents", "hello.txt", 15.minutes())

        presigned shouldBeEqualTo expected
        val timer = registry.find(MicrometerS3KtorClient.DEFAULT_METER_NAME)
            .tag(KtorMetricContract.TAG_OPERATION, MicrometerS3KtorClient.OPERATION_PRESIGN_GET_OBJECT)
            .tag(KtorMetricContract.TAG_OUTCOME, KtorMetricContract.OUTCOME_SUCCESS)
            .tag(KtorMetricContract.TAG_BUCKET, "documents")
            .timer()

        timer.shouldNotBeNull()
        timer.count() shouldBeEqualTo 1L
    }

    @Test
    fun `toString does not invoke injected registry toString`() {
        val registry = mockk<MeterRegistry>()
        every { registry.toString() } returns "meter-registry-secret"

        val rendered = MicrometerS3KtorClient(delegate, registry).toString()

        rendered shouldNotContain "meter-registry-secret"
        verify(exactly = 0) { registry.toString() }
    }
}
