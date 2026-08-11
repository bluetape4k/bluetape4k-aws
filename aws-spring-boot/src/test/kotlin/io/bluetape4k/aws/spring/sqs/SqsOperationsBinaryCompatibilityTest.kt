package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.junit.jupiter.api.Test

class SqsOperationsBinaryCompatibilityTest {

    @Test
    fun `batch operations remain JVM interface defaults for old implementations`() = runSuspendIO {
        val deleteBatch = SqsOperations::class.java.methods.single {
            it.name == "deleteBatch" && it.parameterTypes.firstOrNull() == String::class.java
        }
        val visibilityBatch = SqsOperations::class.java.methods.single {
            it.name == "changeVisibilityBatch" && it.parameterTypes.firstOrNull() == String::class.java
        }
        deleteBatch.isDefault.shouldBeTrue()
        visibilityBatch.isDefault.shouldBeTrue()

        NoopSqsOperations.deleteBatch(QUEUE_URL, listOf("receipt-1", "receipt-2"))
            .successfulEntryIds shouldBeEqualTo listOf("entry-0", "entry-1")
        NoopSqsOperations.changeVisibilityBatch(
            QUEUE_URL,
            listOf(
                SqsChangeVisibilityRequest("message-1", "receipt-1", 0),
                SqsChangeVisibilityRequest("message-2", "receipt-2", 30),
            ),
        ).successfulMessageIds shouldBeEqualTo listOf("message-1", "message-2")
    }

    companion object {
        private const val QUEUE_URL = "https://sqs.local/orders"
    }
}
