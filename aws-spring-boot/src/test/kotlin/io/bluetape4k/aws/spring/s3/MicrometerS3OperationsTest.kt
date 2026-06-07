package io.bluetape4k.aws.spring.s3

import io.bluetape4k.aws.spring.observability.AwsMetricContract
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
            .tag(AwsMetricContract.TAG_OPERATION, AwsMetricContract.OPERATION_UPLOAD)
            .tag(AwsMetricContract.TAG_OUTCOME, AwsMetricContract.OUTCOME_SUCCESS)
            .tag(AwsMetricContract.TAG_SERVICE, AwsMetricContract.SERVICE_S3)
            .timer()
        timer.shouldNotBeNull()
        timer.count() shouldBeEqualTo 1L
        registry.find(MicrometerS3Operations.DEFAULT_METER_NAME)
            .tag(AwsMetricContract.TAG_BUCKET, "documents")
            .timer() shouldBeEqualTo null
    }
}
