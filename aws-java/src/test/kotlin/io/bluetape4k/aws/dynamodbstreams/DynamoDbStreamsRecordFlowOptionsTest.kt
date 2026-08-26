package io.bluetape4k.aws.dynamodbstreams

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DynamoDbStreamsRecordFlowOptionsTest {

    @Test
    fun `defaults respect service and polling limits`() {
        val options = DynamoDbStreamsRecordFlowOptions()

        options.batchLimit shouldBeEqualTo 100
        options.pollInterval shouldBeEqualTo 200.milliseconds
        options.emptyBackoff shouldBeEqualTo 1.seconds
        options.maxShardConcurrency shouldBeEqualTo 4
    }

    @Test
    fun `invalid limits fail fast`() {
        assertFailsWith<IllegalArgumentException> { DynamoDbStreamsRecordFlowOptions(batchLimit = 0) }
        assertFailsWith<IllegalArgumentException> { DynamoDbStreamsRecordFlowOptions(batchLimit = 1_001) }
        assertFailsWith<IllegalArgumentException> {
            DynamoDbStreamsRecordFlowOptions(pollInterval = 199.milliseconds)
        }
        assertFailsWith<IllegalArgumentException> {
            DynamoDbStreamsRecordFlowOptions(emptyBackoff = 199.milliseconds)
        }
        assertFailsWith<IllegalArgumentException> { DynamoDbStreamsRecordFlowOptions(maxShardConcurrency = 0) }
        assertFailsWith<IllegalArgumentException> { DynamoDbStreamsRecordFlowOptions(maxDescribePages = 0) }
    }
}
