package io.bluetape4k.aws.kotlin.kinesis

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class KinesisRecordFlowOptionsTest {

    companion object : KLogging()

    @Test
    fun `default options are valid`() {
        val opts = KinesisRecordFlowOptions()
        opts.batchLimit shouldBeEqualTo KinesisRecordFlowOptions.DEFAULT_BATCH_LIMIT
        opts.pollInterval shouldBeEqualTo KinesisRecordFlowOptions.DEFAULT_POLL_INTERVAL
        opts.emptyBackoff shouldBeEqualTo KinesisRecordFlowOptions.DEFAULT_EMPTY_BACKOFF
        opts.maxIteratorRetries shouldBeEqualTo KinesisRecordFlowOptions.DEFAULT_MAX_ITERATOR_RETRIES
        opts.initialThrottleBackoff shouldBeEqualTo KinesisRecordFlowOptions.DEFAULT_INITIAL_THROTTLE_BACKOFF
        opts.maxThrottleBackoff shouldBeEqualTo KinesisRecordFlowOptions.DEFAULT_MAX_THROTTLE_BACKOFF
        opts.maxThrottleRetries shouldBeEqualTo KinesisRecordFlowOptions.DEFAULT_MAX_THROTTLE_RETRIES
    }

    @Test
    fun `batchLimit at boundaries is accepted`() {
        KinesisRecordFlowOptions(batchLimit = 1)
        KinesisRecordFlowOptions(batchLimit = KinesisRecordFlowOptions.MAX_KINESIS_BATCH_LIMIT)
    }

    @Test
    fun `batchLimit zero is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            KinesisRecordFlowOptions(batchLimit = 0)
        }
    }

    @Test
    fun `batchLimit above max is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            KinesisRecordFlowOptions(batchLimit = KinesisRecordFlowOptions.MAX_KINESIS_BATCH_LIMIT + 1)
        }
    }

    @Test
    fun `pollInterval at minimum is accepted`() {
        KinesisRecordFlowOptions(pollInterval = KinesisRecordFlowOptions.MIN_POLL_INTERVAL)
    }

    @Test
    fun `pollInterval below minimum is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            KinesisRecordFlowOptions(pollInterval = 100.milliseconds)
        }
    }

    @Test
    fun `emptyBackoff must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            KinesisRecordFlowOptions(emptyBackoff = 0.milliseconds)
        }
    }

    @Test
    fun `maxIteratorRetries of zero means no retry`() {
        val opts = KinesisRecordFlowOptions(maxIteratorRetries = 0)
        opts.maxIteratorRetries shouldBeEqualTo 0
    }

    @Test
    fun `maxIteratorRetries negative is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            KinesisRecordFlowOptions(maxIteratorRetries = -1)
        }
    }

    @Test
    fun `maxThrottleBackoff must be ge initialThrottleBackoff`() {
        assertFailsWith<IllegalArgumentException> {
            KinesisRecordFlowOptions(
                initialThrottleBackoff = 10.seconds,
                maxThrottleBackoff = 1.seconds,
            )
        }
    }

    @Test
    fun `maxThrottleBackoff equal to initialThrottleBackoff is valid`() {
        KinesisRecordFlowOptions(
            initialThrottleBackoff = 5.seconds,
            maxThrottleBackoff = 5.seconds,
        )
    }

    @Test
    fun `maxThrottleRetries of zero means no retry`() {
        val opts = KinesisRecordFlowOptions(maxThrottleRetries = 0)
        opts.maxThrottleRetries shouldBeEqualTo 0
    }

    @Test
    fun `copy produces independent instance`() {
        val original = KinesisRecordFlowOptions()
        val copy = original.copy(batchLimit = 50)
        copy.batchLimit shouldBeEqualTo 50
        original.batchLimit shouldBeEqualTo KinesisRecordFlowOptions.DEFAULT_BATCH_LIMIT
    }
}
