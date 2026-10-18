package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException

class SqsExtendedClientExceptionsTest {

    companion object: KLogging()

    @Test
    fun `send factories preserve typed invariant and bounded diagnostic`() {
        val upload = SqsExtendedSendException.upload()
        log.debug { "upload=$upload" }
        upload.pointerPresent.shouldBeFalse()
        upload.orphanCleanupRequired.shouldBeFalse()
        upload.failureKind shouldBeEqualTo SqsExtendedFailureKind.S3_UPLOAD
        upload.retryable.shouldBeTrue()
        upload.diagnosticCode shouldBeEqualTo SqsExtendedDiagnosticCode.S3_UPLOAD.value

        val inline = SqsExtendedSendException.inlineSqs()
        log.debug { "inline=$inline" }
        inline.pointerPresent.shouldBeFalse()
        inline.orphanCleanupRequired.shouldBeFalse()
        inline.failureKind shouldBeEqualTo SqsExtendedFailureKind.SQS_SEND

        val offloaded = SqsExtendedSendException.offloadedSqs()
        log.debug { "offloaded=$offloaded" }
        offloaded.pointerPresent.shouldBeTrue()
        offloaded.orphanCleanupRequired.shouldBeTrue()
        offloaded.failureKind shouldBeEqualTo SqsExtendedFailureKind.SQS_SEND
        offloaded.message shouldBeEqualTo SqsExtendedDiagnosticCode.SQS_SEND.value
        offloaded.cause.shouldBeNull()
        offloaded.suppressed.shouldBeEmpty()
    }

    @Test
    fun `cleanup and acknowledgement factories reject inconsistent result state`() {
        val ack = SqsExtendedAcknowledgementException.create(
            sqsDeleted = true,
            cleanupRequired = true,
        )
        log.debug { "acknowledgement=$ack" }
        ack.sqsDeleted.shouldBeTrue()
        ack.cleanupRequired.shouldBeTrue()
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
        log.debug { "cancellation=$cancellation" }
        cancellation.shouldBeInstanceOf<CancellationException>()
        cancellation.toString() shouldNotContain secret
        cancellation.stackTrace.size shouldBeEqualTo 0

        val timeout = SqsExtendedDrainTimeoutException(activeOperations = 2)
        log.debug { "timeout=$timeout" }
        timeout.activeOperations shouldBeEqualTo 2
        timeout.diagnosticCode shouldBeEqualTo SqsExtendedDiagnosticCode.DRAIN_TIMEOUT.value
        timeout.stackTrace.size shouldBeEqualTo 0
    }
}
