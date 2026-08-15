package io.bluetape4k.aws.spring.sns

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException
import software.amazon.awssdk.core.exception.ApiCallTimeoutException
import software.amazon.awssdk.services.sns.model.SnsException
import java.util.concurrent.TimeoutException

class SnsBatchExceptionsTest {

    @Test
    fun `transport exception keeps safe type and completed ids without raw cause`() {
        val raw = IllegalStateException("payload-secret\r\narn-secret")
        val exception = SnsBatchTransportException.from(raw, listOf("entry-secret"))

        exception.failureType shouldBeEqualTo SnsBatchFailureType.UNKNOWN
        exception.completedEntryIds shouldBeEqualTo listOf("entry-secret")
        exception.cause.shouldBeNull()
        exception.suppressed.shouldBeEmpty()

        val rendered = buildString {
            append(exception.message)
            append(exception)
            exception.stackTrace.forEach { append(it) }
            exception.suppressed.forEach { append(it) }
        }
        rendered shouldNotContain "payload-secret"
        rendered shouldNotContain "arn-secret"
        rendered shouldNotContain "\r"
        rendered shouldNotContain "\n"
    }

    @Test
    fun `transport exception classifies concrete SNS service exceptions`() {
        val exception = SnsBatchTransportException.from(
            SnsException.builder().message("service-secret").build(),
            emptyList(),
        )

        exception.failureType shouldBeEqualTo SnsBatchFailureType.SDK_SERVICE
        exception.toString() shouldNotContain "service-secret"
    }

    @Test
    fun `transport exception classifies only explicit timeout types`() {
        SnsBatchTransportException.from(
            ApiCallTimeoutException.create(100),
            emptyList(),
        ).failureType shouldBeEqualTo SnsBatchFailureType.TIMEOUT

        SnsBatchTransportException.from(
            ApiCallAttemptTimeoutException.create(100),
            emptyList(),
        ).failureType shouldBeEqualTo SnsBatchFailureType.TIMEOUT

        SnsBatchTransportException.from(TimeoutException("timeout"), emptyList())
            .failureType shouldBeEqualTo SnsBatchFailureType.TIMEOUT

        SnsBatchTransportException.from(NamedTimeoutFailure(), emptyList())
            .failureType shouldBeEqualTo SnsBatchFailureType.UNKNOWN
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
        exception.completedEntryIds.shouldBeEmpty()

        val rendered = "${exception.message}$exception"
        rendered shouldNotContain "entry-1"
        rendered shouldNotContain "entry-secret"
    }
}

private class NamedTimeoutFailure : RuntimeException("timeout")
