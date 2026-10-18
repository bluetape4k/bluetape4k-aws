package io.bluetape4k.aws.spring.kinesis

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class KinesisRecordFlowOptionsTest {

    @Test
    fun `zero retry limits disable retries`() {
        val options = KinesisRecordFlowOptions(
            maxIteratorRetries = 0,
            maxThrottleRetries = 0,
        )

        options.maxIteratorRetries shouldBeEqualTo 0
        options.maxThrottleRetries shouldBeEqualTo 0
    }

    @Test
    fun `negative retry limits are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            KinesisRecordFlowOptions(maxIteratorRetries = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            KinesisRecordFlowOptions(maxThrottleRetries = -1)
        }
    }
}
