package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.codec.Base58
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException

class SqsExtendedClientExceptionsTest {

    @Test
    fun `send factories preserve typed invariant and bounded diagnostic`() {
        val upload = SqsExtendedSendException.upload()
        upload.pointerPresent shouldBeEqualTo false
        upload.orphanCleanupRequired shouldBeEqualTo false
        upload.failureKind shouldBeEqualTo SqsExtendedFailureKind.S3_UPLOAD
        upload.retryable shouldBeEqualTo true
        upload.diagnosticCode shouldBeEqualTo SqsExtendedDiagnosticCode.S3_UPLOAD.value

        val inline = SqsExtendedSendException.inlineSqs()
        inline.pointerPresent shouldBeEqualTo false
        inline.orphanCleanupRequired shouldBeEqualTo false
        inline.failureKind shouldBeEqualTo SqsExtendedFailureKind.SQS_SEND

        val offloaded = SqsExtendedSendException.offloadedSqs()
        offloaded.pointerPresent shouldBeEqualTo true
        offloaded.orphanCleanupRequired shouldBeEqualTo true
        offloaded.failureKind shouldBeEqualTo SqsExtendedFailureKind.SQS_SEND
        offloaded.message shouldBeEqualTo SqsExtendedDiagnosticCode.SQS_SEND.value
        offloaded.cause shouldBeEqualTo null
        offloaded.suppressed.shouldBeEmpty()
    }

    @Test
    fun `cleanup and acknowledgement factories reject inconsistent result state`() {
        val ack = SqsExtendedAcknowledgementException.create(
            sqsDeleted = true,
            cleanupRequired = true,
        )
        ack.sqsDeleted shouldBeEqualTo true
        ack.cleanupRequired shouldBeEqualTo true
        ack.failureKind shouldBeEqualTo SqsExtendedFailureKind.SQS_ACK

        runCatching {
            SqsExtendedAcknowledgementResult.create(
                sqsDeleted = true,
                payloadDeleted = false,
                cleanupRequired = true,
                pointer = null,
            )
        }.exceptionOrNull()?.javaClass shouldBeEqualTo IllegalArgumentException::class.java

        runCatching {
            SqsExtendedCleanupResult.create(
                deleted = true,
                cleanupRequired = true,
                failureKind = SqsExtendedFailureKind.S3_DELETE,
                retryable = true,
            )
        }.exceptionOrNull()?.javaClass shouldBeEqualTo IllegalArgumentException::class.java
    }

    @Test
    fun `cancellation and timeout exceptions redact external identity`() {
        val secret = Base58.randomString(16)
        val cancellation = SqsExtendedCancellationException.create(
            failureKind = SqsExtendedFailureKind.SQS_SEND,
            pointerPresent = true,
            orphanCleanupRequired = true,
        )
        cancellation.shouldBeInstanceOf<CancellationException>()
        cancellation.toString() shouldNotContain secret
        cancellation.stackTrace.size shouldBeEqualTo 0

        val timeout = SqsExtendedDrainTimeoutException(activeOperations = 2)
        timeout.activeOperations shouldBeEqualTo 2
        timeout.diagnosticCode shouldBeEqualTo SqsExtendedDiagnosticCode.DRAIN_TIMEOUT.value
        timeout.stackTrace.size shouldBeEqualTo 0
    }
}
