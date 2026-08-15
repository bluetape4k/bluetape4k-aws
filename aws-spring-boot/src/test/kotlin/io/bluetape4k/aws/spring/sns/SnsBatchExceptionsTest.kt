package io.bluetape4k.aws.spring.sns

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import org.junit.jupiter.api.Test

class SnsBatchExceptionsTest {

    @Test
    fun `transport exception keeps safe type and completed ids without raw cause`() {
        val raw = IllegalStateException("payload-secret\r\narn-secret")
        val exception = SnsBatchTransportException.from(raw, listOf("entry-secret"))

        exception.failureType shouldBeEqualTo SnsBatchFailureType.UNKNOWN
        exception.completedEntryIds shouldBeEqualTo listOf("entry-secret")
        check(exception.cause == null)
        check(exception.suppressed.isEmpty())

        val rendered = buildString {
            append(exception.message)
            append(exception)
            exception.stackTrace.forEach { append(it) }
            exception.suppressed.forEach { append(it) }
        }
        check("payload-secret" !in rendered)
        check("arn-secret" !in rendered)
        check("\r" !in rendered)
        check("\n" !in rendered)
    }

    @Test
    fun `protocol exception reports unknown duplicate and missing counts only`() {
        val exception = SnsBatchProtocolException.from(
            submittedEntryIds = listOf("entry-1", "entry-2"),
            responseEntryIds = listOf("entry-1", "entry-1", "entry-secret"),
        )

        exception.submittedEntryCount shouldBeEqualTo 2
        exception.responseEntryCount shouldBeEqualTo 3
        exception.unknownEntryCount shouldBeEqualTo 1
        exception.duplicateEntryCount shouldBeEqualTo 1
        exception.missingEntryCount shouldBeEqualTo 1
        exception.completedEntryIds shouldHaveSize 0

        val rendered = "${exception.message}$exception"
        check("entry-1" !in rendered)
        check("entry-secret" !in rendered)
    }
}
