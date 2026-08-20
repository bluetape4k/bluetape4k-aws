@file:Suppress("MaxLineLength")

package io.bluetape4k.aws.spring.sqs

import java.util.concurrent.CancellationException

enum class SqsExtendedFailureKind {
    CONFIGURATION,
    POINTER_FORMAT,
    S3_UPLOAD,
    S3_READ,
    S3_DELETE,
    SQS_SEND,
    SQS_ACK,
    DRAIN_TIMEOUT,
}

enum class SqsExtendedDiagnosticCode(val value: String) {
    CONFIGURATION("SQS_EXT_CONFIG_001"),
    POINTER_FORMAT("SQS_EXT_POINTER_001"),
    S3_UPLOAD("SQS_EXT_S3_UPLOAD_001"),
    S3_READ("SQS_EXT_S3_READ_001"),
    S3_DELETE("SQS_EXT_S3_DELETE_001"),
    SQS_SEND("SQS_EXT_SQS_SEND_001"),
    SQS_ACK("SQS_EXT_SQS_ACK_001"),
    CANCEL("SQS_EXT_CANCEL_001"),
    DRAIN_TIMEOUT("SQS_EXT_DRAIN_001"),
    DRAIN_ADMISSION("SQS_EXT_DRAIN_002"),
}

sealed class SqsExtendedClientException(
    val failureKind: SqsExtendedFailureKind,
    val retryable: Boolean,
    val diagnosticCode: String,
) : RuntimeException(diagnosticCode, null, false, false)

class SqsExtendedConfigurationException private constructor(
    retryable: Boolean,
    diagnostic: SqsExtendedDiagnosticCode,
) : SqsExtendedClientException(SqsExtendedFailureKind.CONFIGURATION, retryable, diagnostic.value) {
    internal companion object {
        fun create(retryable: Boolean = false): SqsExtendedConfigurationException =
            SqsExtendedConfigurationException(retryable, SqsExtendedDiagnosticCode.CONFIGURATION)

        fun drainAdmission(): SqsExtendedConfigurationException =
            SqsExtendedConfigurationException(true, SqsExtendedDiagnosticCode.DRAIN_ADMISSION)
    }
}

class SqsExtendedPointerFormatException private constructor() : SqsExtendedClientException(
    SqsExtendedFailureKind.POINTER_FORMAT,
    false,
    SqsExtendedDiagnosticCode.POINTER_FORMAT.value,
) {
    internal companion object {
        fun create(): SqsExtendedPointerFormatException = SqsExtendedPointerFormatException()
    }
}

class SqsExtendedPayloadReadException private constructor(
    val pointerPresent: Boolean,
    retryable: Boolean,
) : SqsExtendedClientException(SqsExtendedFailureKind.S3_READ, retryable, SqsExtendedDiagnosticCode.S3_READ.value) {
    internal companion object {
        fun create(pointerPresent: Boolean, retryable: Boolean): SqsExtendedPayloadReadException =
            SqsExtendedPayloadReadException(pointerPresent, retryable)
    }
}

class SqsExtendedSendException private constructor(
    val pointerPresent: Boolean,
    val orphanCleanupRequired: Boolean,
    failureKind: SqsExtendedFailureKind,
    retryable: Boolean,
    diagnostic: SqsExtendedDiagnosticCode,
) : SqsExtendedClientException(failureKind, retryable, diagnostic.value) {
    init {
        require(orphanCleanupRequired || !pointerPresent)
        require(failureKind == SqsExtendedFailureKind.S3_UPLOAD || failureKind == SqsExtendedFailureKind.SQS_SEND)
    }

    internal companion object {
        fun upload(): SqsExtendedSendException =
            SqsExtendedSendException(false, false, SqsExtendedFailureKind.S3_UPLOAD, true, SqsExtendedDiagnosticCode.S3_UPLOAD)

        fun inlineSqs(): SqsExtendedSendException =
            SqsExtendedSendException(false, false, SqsExtendedFailureKind.SQS_SEND, true, SqsExtendedDiagnosticCode.SQS_SEND)

        fun offloadedSqs(): SqsExtendedSendException =
            SqsExtendedSendException(true, true, SqsExtendedFailureKind.SQS_SEND, true, SqsExtendedDiagnosticCode.SQS_SEND)
    }
}

class SqsExtendedAcknowledgementException private constructor(
    val sqsDeleted: Boolean,
    val cleanupRequired: Boolean,
    retryable: Boolean,
) : SqsExtendedClientException(SqsExtendedFailureKind.SQS_ACK, retryable, SqsExtendedDiagnosticCode.SQS_ACK.value) {
    internal companion object {
        fun create(
            sqsDeleted: Boolean,
            cleanupRequired: Boolean,
            retryable: Boolean = true,
        ): SqsExtendedAcknowledgementException =
            SqsExtendedAcknowledgementException(sqsDeleted, cleanupRequired, retryable)
    }
}

class SqsExtendedCleanupException private constructor(
    val handlePresent: Boolean,
    failureKind: SqsExtendedFailureKind,
    retryable: Boolean,
    diagnostic: SqsExtendedDiagnosticCode,
) : SqsExtendedClientException(failureKind, retryable, diagnostic.value) {
    internal companion object {
        fun configuration(handlePresent: Boolean = false): SqsExtendedCleanupException =
            SqsExtendedCleanupException(handlePresent, SqsExtendedFailureKind.CONFIGURATION, false, SqsExtendedDiagnosticCode.CONFIGURATION)

        fun delete(handlePresent: Boolean = true): SqsExtendedCleanupException =
            SqsExtendedCleanupException(handlePresent, SqsExtendedFailureKind.S3_DELETE, true, SqsExtendedDiagnosticCode.S3_DELETE)
    }
}

class SqsExtendedDrainTimeoutException(
    val activeOperations: Int,
) : SqsExtendedClientException(
    SqsExtendedFailureKind.DRAIN_TIMEOUT,
    true,
    SqsExtendedDiagnosticCode.DRAIN_TIMEOUT.value,
) {
    init {
        require(activeOperations >= 0)
    }
}

class SqsExtendedCancellationException private constructor(
    val failureKind: SqsExtendedFailureKind,
    val pointerPresent: Boolean,
    val orphanCleanupRequired: Boolean,
    val diagnosticCode: String,
) : CancellationException() {
    init {
        require(!orphanCleanupRequired || pointerPresent)
        require(failureKind == SqsExtendedFailureKind.S3_UPLOAD || failureKind == SqsExtendedFailureKind.SQS_SEND)
    }

    override fun fillInStackTrace(): Throwable = this

    internal companion object {
        fun create(
            failureKind: SqsExtendedFailureKind,
            pointerPresent: Boolean,
            orphanCleanupRequired: Boolean,
        ): SqsExtendedCancellationException =
            SqsExtendedCancellationException(
                failureKind,
                pointerPresent,
                orphanCleanupRequired,
                SqsExtendedDiagnosticCode.CANCEL.value,
            )
    }
}
