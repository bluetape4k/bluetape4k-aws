package io.bluetape4k.aws.spring.s3

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MicrometerS3OperationsTest {

    @Test
    fun `record S3 upload operation timer without bucket tag by default`() = runSuspendIO {
        val registry = SimpleMeterRegistry()
        val operations = MicrometerS3Operations(NoopS3Operations, registry)

        operations.upload("documents", "hello.txt", "hello".encodeToByteArray(), "text/plain")

        val timer = registry.find(MicrometerS3Operations.DEFAULT_METER_NAME)
            .tag("operation", "upload")
            .tag("outcome", "success")
            .tag("service", "s3")
            .timer()
        timer.shouldNotBeNull()
        timer.count() shouldBeEqualTo 1L
        registry.find(MicrometerS3Operations.DEFAULT_METER_NAME)
            .tag("bucket", "documents")
            .timer() shouldBeEqualTo null
    }
}
