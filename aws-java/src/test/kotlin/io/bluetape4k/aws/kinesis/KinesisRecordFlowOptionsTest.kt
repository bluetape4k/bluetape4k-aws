package io.bluetape4k.aws.kinesis

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import kotlin.time.Duration.Companion.milliseconds
import org.junit.jupiter.api.Test

class KinesisRecordFlowOptionsTest {

    @Test
    fun `defaults match the consumer polling contract`() {
        val options = KinesisRecordFlowOptions()

        options.batchLimit shouldBeEqualTo KinesisRecordFlowOptions.DEFAULT_BATCH_LIMIT
        options.pollInterval shouldBeEqualTo KinesisRecordFlowOptions.DEFAULT_POLL_INTERVAL
        options.emptyBackoff shouldBeEqualTo KinesisRecordFlowOptions.DEFAULT_EMPTY_BACKOFF
        options.maxIteratorRetries shouldBeEqualTo KinesisRecordFlowOptions.DEFAULT_MAX_ITERATOR_RETRIES
        options.maxThrottleRetries shouldBeEqualTo KinesisRecordFlowOptions.DEFAULT_MAX_THROTTLE_RETRIES
    }

    @Test
    fun `poll interval and batch limits are bounded`() {
        assertFailsWith<IllegalArgumentException> {
            KinesisRecordFlowOptions(batchLimit = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            KinesisRecordFlowOptions(batchLimit = KinesisRecordFlowOptions.MAX_KINESIS_BATCH_LIMIT + 1)
        }
        assertFailsWith<IllegalArgumentException> {
            KinesisRecordFlowOptions(pollInterval = 199.milliseconds)
        }
        assertFailsWith<IllegalArgumentException> {
            KinesisRecordFlowOptions(emptyBackoff = 0.milliseconds)
        }
    }

    @Test
    fun `consumer clamps a positive empty backoff below the kinesis cadence`() {
        KinesisRecordFlowOptions(emptyBackoff = 1.milliseconds).effectiveEmptyBackoff shouldBeEqualTo
                KinesisRecordFlowOptions.MIN_POLL_INTERVAL
    }
}
