package io.bluetape4k.aws.spring.modulith

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class AwsModulithExceptionsTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("exceptionCatalog")
    fun `public exception catalog is stable`(
        name: String,
        exception: AwsModulithEventException,
        expectedType: Class<*>,
        expectedCode: AwsModulithDiagnosticCode,
        expectedPhase: AwsModulithFailurePhase,
        expectedRetryable: Boolean,
        expectedAction: AwsModulithCallerAction,
    ) {
        exception.javaClass shouldBeEqualTo expectedType
        exception.code shouldBeEqualTo expectedCode
        exception.phase shouldBeEqualTo expectedPhase
        exception.retryable shouldBeEqualTo expectedRetryable
        exception.callerAction shouldBeEqualTo expectedAction
        exception.message shouldBeEqualTo "${expectedCode.value}:${expectedPhase.name}"
        exception.cause.shouldBeNull()
        exception.suppressed.shouldBeEmpty()
        exception.message shouldNotContain HOSTILE_MARKER

        assertFailsWith<IllegalStateException> {
            exception.initCause(IllegalStateException(HOSTILE_MARKER))
        }
        exception.addSuppressed(AwsModulithCleanupException())
        exception.suppressed shouldHaveSize 1
    }

    @Test
    fun `diagnostic code mapping is fixed`() {
        AwsModulithDiagnosticCode.CONFIGURATION.callerAction shouldBeEqualTo AwsModulithCallerAction.STOP_DEPLOYMENT

        AwsModulithDiagnosticCode.CONFIGURATION.retryable.shouldBeFalse()
        AwsModulithDiagnosticCode.PRODUCER_LIFECYCLE.retryable.shouldBeTrue()
        AwsModulithDiagnosticCode.AWS_PUBLISH.retryable.shouldBeTrue()
        AwsModulithDiagnosticCode.SOURCE.retryable.shouldBeFalse()
        AwsModulithDiagnosticCode.INBOUND.retryable.shouldBeFalse()
        AwsModulithDiagnosticCode.CLAIM.retryable.shouldBeTrue()
        AwsModulithDiagnosticCode.DISPATCH_ACK.retryable.shouldBeTrue()
    }

    @Test
    fun `cleanup exception stays internal and bounded`() {
        val exception = AwsModulithCleanupException()

        exception.code shouldBeEqualTo AwsModulithDiagnosticCode.DISPATCH_ACK
        exception.phase shouldBeEqualTo AwsModulithFailurePhase.CLEANUP
        exception.message shouldBeEqualTo "BT4K-MOD-204:CLEANUP"
        exception.cause.shouldBeNull()
    }

    companion object: KLogging() {
        private const val HOSTILE_MARKER = "secret-value:event-id:header-value:arn:request-response"

        @JvmStatic
        fun exceptionCatalog(): Stream<Arguments> =
            Stream.of(*exceptionCatalogEntries().toTypedArray())

        private fun exceptionCatalogEntries(): List<Arguments> =
            configurationEntries() +
                    producerEntries() +
                    resolutionEntries() +
                    inboundEntries() +
                    claimEntries() +
                    dispatchEntries()

        private fun configurationEntries() = listOf(
            Arguments.of(
                "configuration",
                AwsModulithConfigurationException(),
                AwsModulithConfigurationException::class.java,
                AwsModulithDiagnosticCode.CONFIGURATION,
                AwsModulithFailurePhase.CONFIGURATION,
                false,
                AwsModulithCallerAction.STOP_DEPLOYMENT,
            ),
            Arguments.of(
                "registration mismatch",
                AwsModulithEventRegistrationMismatchException(),
                AwsModulithEventRegistrationMismatchException::class.java,
                AwsModulithDiagnosticCode.ENVELOPE,
                AwsModulithFailurePhase.SERIALIZATION,
                false,
                AwsModulithCallerAction.FIX_PAYLOAD,
            ),
            Arguments.of(
                "outbound envelope",
                AwsModulithOutboundEnvelopeException(),
                AwsModulithOutboundEnvelopeException::class.java,
                AwsModulithDiagnosticCode.ENVELOPE,
                AwsModulithFailurePhase.SERIALIZATION,
                false,
                AwsModulithCallerAction.FIX_PAYLOAD,
            ),
        )

        private fun producerEntries() = listOf(
            Arguments.of(
                "producer capacity",
                AwsModulithProducerCapacityException(),
                AwsModulithProducerCapacityException::class.java,
                AwsModulithDiagnosticCode.PRODUCER_LIFECYCLE,
                AwsModulithFailurePhase.LIFECYCLE,
                true,
                AwsModulithCallerAction.RESUBMIT_PUBLICATION,
            ),
            Arguments.of(
                "producer closed",
                AwsModulithProducerClosedException(),
                AwsModulithProducerClosedException::class.java,
                AwsModulithDiagnosticCode.PRODUCER_LIFECYCLE,
                AwsModulithFailurePhase.LIFECYCLE,
                true,
                AwsModulithCallerAction.RESUBMIT_PUBLICATION,
            ),
        )

        private fun resolutionEntries() = listOf(
            Arguments.of(
                "target resolution",
                AwsModulithTargetResolutionException(),
                AwsModulithTargetResolutionException::class.java,
                AwsModulithDiagnosticCode.AWS_PUBLISH,
                AwsModulithFailurePhase.RESOLUTION,
                true,
                AwsModulithCallerAction.CHECK_AWS_AND_RESUBMIT,
            ),
            Arguments.of(
                "publish",
                AwsModulithPublishException(),
                AwsModulithPublishException::class.java,
                AwsModulithDiagnosticCode.AWS_PUBLISH,
                AwsModulithFailurePhase.PUBLISH,
                true,
                AwsModulithCallerAction.CHECK_AWS_AND_RESUBMIT,
            ),
        )

        private fun inboundEntries() = listOf(
            Arguments.of(
                "source",
                AwsModulithSourceException(),
                AwsModulithSourceException::class.java,
                AwsModulithDiagnosticCode.SOURCE,
                AwsModulithFailurePhase.SOURCE,
                false,
                AwsModulithCallerAction.QUARANTINE_SOURCE,
            ),
            Arguments.of(
                "inbound envelope",
                AwsModulithInboundEnvelopeException(),
                AwsModulithInboundEnvelopeException::class.java,
                AwsModulithDiagnosticCode.INBOUND,
                AwsModulithFailurePhase.DECODE,
                false,
                AwsModulithCallerAction.DEPLOY_COMPATIBLE_CONSUMER,
            ),
            Arguments.of(
                "unknown event type",
                AwsModulithUnknownEventTypeException(),
                AwsModulithUnknownEventTypeException::class.java,
                AwsModulithDiagnosticCode.INBOUND,
                AwsModulithFailurePhase.DECODE,
                false,
                AwsModulithCallerAction.DEPLOY_COMPATIBLE_CONSUMER,
            ),
            Arguments.of(
                "unsupported event version",
                AwsModulithUnsupportedEventVersionException(),
                AwsModulithUnsupportedEventVersionException::class.java,
                AwsModulithDiagnosticCode.INBOUND,
                AwsModulithFailurePhase.DECODE,
                false,
                AwsModulithCallerAction.DEPLOY_COMPATIBLE_CONSUMER,
            ),
            Arguments.of(
                "inbound loop risk",
                AwsModulithInboundLoopRiskException(),
                AwsModulithInboundLoopRiskException::class.java,
                AwsModulithDiagnosticCode.INBOUND,
                AwsModulithFailurePhase.DECODE,
                false,
                AwsModulithCallerAction.DEPLOY_COMPATIBLE_CONSUMER,
            ),
        )

        private fun claimEntries() = listOf(
            Arguments.of(
                "claim capacity",
                AwsModulithClaimCapacityException(),
                AwsModulithClaimCapacityException::class.java,
                AwsModulithDiagnosticCode.CLAIM,
                AwsModulithFailurePhase.CLAIM,
                true,
                AwsModulithCallerAction.RECOVER_STORE_AND_RETRY,
            ),
            Arguments.of(
                "event in progress",
                AwsModulithEventInProgressException(),
                AwsModulithEventInProgressException::class.java,
                AwsModulithDiagnosticCode.CLAIM,
                AwsModulithFailurePhase.CLAIM,
                true,
                AwsModulithCallerAction.RECOVER_STORE_AND_RETRY,
            ),
            Arguments.of(
                "stale claim",
                AwsModulithStaleClaimException(),
                AwsModulithStaleClaimException::class.java,
                AwsModulithDiagnosticCode.CLAIM,
                AwsModulithFailurePhase.CLAIM,
                true,
                AwsModulithCallerAction.RECOVER_STORE_AND_RETRY,
            ),
            Arguments.of(
                "claim mutation",
                AwsModulithClaimMutationException(),
                AwsModulithClaimMutationException::class.java,
                AwsModulithDiagnosticCode.CLAIM,
                AwsModulithFailurePhase.CLAIM,
                true,
                AwsModulithCallerAction.RECOVER_STORE_AND_RETRY,
            ),
        )

        private fun dispatchEntries() = listOf(
            Arguments.of(
                "dispatch",
                AwsModulithDispatchException(),
                AwsModulithDispatchException::class.java,
                AwsModulithDiagnosticCode.DISPATCH_ACK,
                AwsModulithFailurePhase.DISPATCH,
                true,
                AwsModulithCallerAction.INSPECT_DISPATCH_OR_ACK,
            ),
            Arguments.of(
                "acknowledgement",
                AwsModulithAcknowledgementException(),
                AwsModulithAcknowledgementException::class.java,
                AwsModulithDiagnosticCode.DISPATCH_ACK,
                AwsModulithFailurePhase.ACK,
                true,
                AwsModulithCallerAction.INSPECT_DISPATCH_OR_ACK,
            ),
        )
    }
}
