package io.bluetape4k.aws.spring.modulith

/** 운영 caller가 재시도와 조치를 결정할 수 있는 bounded action입니다. */
enum class AwsModulithCallerAction {
    STOP_DEPLOYMENT,
    FIX_PAYLOAD,
    RESUBMIT_PUBLICATION,
    CHECK_AWS_AND_RESUBMIT,
    QUARANTINE_SOURCE,
    DEPLOY_COMPATIBLE_CONSUMER,
    RECOVER_STORE_AND_RETRY,
    INSPECT_DISPATCH_OR_ACK,
}

/** 외부화 adapter가 노출하는 안정적인 diagnostic code와 retry 정책입니다. */
enum class AwsModulithDiagnosticCode(
    val value: String,
    val retryable: Boolean,
    val callerAction: AwsModulithCallerAction,
) {
    CONFIGURATION("BT4K-MOD-101", false, AwsModulithCallerAction.STOP_DEPLOYMENT),
    ENVELOPE("BT4K-MOD-102", false, AwsModulithCallerAction.FIX_PAYLOAD),
    PRODUCER_LIFECYCLE("BT4K-MOD-103", true, AwsModulithCallerAction.RESUBMIT_PUBLICATION),
    AWS_PUBLISH("BT4K-MOD-104", true, AwsModulithCallerAction.CHECK_AWS_AND_RESUBMIT),
    SOURCE("BT4K-MOD-201", false, AwsModulithCallerAction.QUARANTINE_SOURCE),
    INBOUND("BT4K-MOD-202", false, AwsModulithCallerAction.DEPLOY_COMPATIBLE_CONSUMER),
    CLAIM("BT4K-MOD-203", true, AwsModulithCallerAction.RECOVER_STORE_AND_RETRY),
    DISPATCH_ACK("BT4K-MOD-204", true, AwsModulithCallerAction.INSPECT_DISPATCH_OR_ACK),
}

/** adapter가 실패 단계를 구분하기 위해 사용하는 bounded phase입니다. */
enum class AwsModulithFailurePhase {
    CONFIGURATION,
    SERIALIZATION,
    LIFECYCLE,
    RESOLUTION,
    PUBLISH,
    SOURCE,
    DECODE,
    CLAIM,
    DISPATCH,
    ACK,
    CLEANUP,
}

/**
 * payload, event ID, header, ARN, URL, AWS 응답을 포함하지 않는 공통 실패 타입입니다.
 * retryability와 caller action은 diagnostic code에서만 파생됩니다.
 */
sealed class AwsModulithEventException protected constructor(
    val code: AwsModulithDiagnosticCode,
    val phase: AwsModulithFailurePhase,
) : RuntimeException("${code.value}:${phase.name}", null, true, true) {
    val retryable: Boolean
        get() = code.retryable

    val callerAction: AwsModulithCallerAction
        get() = code.callerAction
}

/** 설정이 현재 application에서 안전하게 시작될 수 없음을 나타냅니다. */
class AwsModulithConfigurationException internal constructor() :
    AwsModulithEventException(AwsModulithDiagnosticCode.CONFIGURATION, AwsModulithFailurePhase.CONFIGURATION)

/** 등록된 이벤트 class와 실제 payload가 일치하지 않음을 나타냅니다. */
class AwsModulithEventRegistrationMismatchException internal constructor() :
    AwsModulithEventException(AwsModulithDiagnosticCode.ENVELOPE, AwsModulithFailurePhase.SERIALIZATION)

/** outbound envelope를 만들 수 없음을 나타냅니다. */
class AwsModulithOutboundEnvelopeException internal constructor() :
    AwsModulithEventException(AwsModulithDiagnosticCode.ENVELOPE, AwsModulithFailurePhase.SERIALIZATION)

/** producer admission 상한을 초과했음을 나타냅니다. */
class AwsModulithProducerCapacityException internal constructor() :
    AwsModulithEventException(AwsModulithDiagnosticCode.PRODUCER_LIFECYCLE, AwsModulithFailurePhase.LIFECYCLE)

/** producer가 종료되어 publication을 받을 수 없음을 나타냅니다. */
class AwsModulithProducerClosedException internal constructor() :
    AwsModulithEventException(AwsModulithDiagnosticCode.PRODUCER_LIFECYCLE, AwsModulithFailurePhase.LIFECYCLE)

/** logical target을 AWS destination으로 해석할 수 없음을 나타냅니다. */
class AwsModulithTargetResolutionException internal constructor() :
    AwsModulithEventException(AwsModulithDiagnosticCode.AWS_PUBLISH, AwsModulithFailurePhase.RESOLUTION)

/** AWS publish가 완료되지 않았음을 나타냅니다. */
class AwsModulithPublishException internal constructor() :
    AwsModulithEventException(AwsModulithDiagnosticCode.AWS_PUBLISH, AwsModulithFailurePhase.PUBLISH)

/** inbound source 또는 signature 검증에 실패했음을 나타냅니다. */
class AwsModulithSourceException internal constructor() :
    AwsModulithEventException(AwsModulithDiagnosticCode.SOURCE, AwsModulithFailurePhase.SOURCE)

/** inbound envelope decode가 실패했음을 나타냅니다. */
class AwsModulithInboundEnvelopeException internal constructor() :
    AwsModulithEventException(AwsModulithDiagnosticCode.INBOUND, AwsModulithFailurePhase.DECODE)

/** 등록되지 않은 event type입니다. */
class AwsModulithUnknownEventTypeException internal constructor() :
    AwsModulithEventException(AwsModulithDiagnosticCode.INBOUND, AwsModulithFailurePhase.DECODE)

/** 등록된 type에 해당 version이 없습니다. */
class AwsModulithUnsupportedEventVersionException internal constructor() :
    AwsModulithEventException(AwsModulithDiagnosticCode.INBOUND, AwsModulithFailurePhase.DECODE)

/** inbound event가 다시 외부화되는 loop를 나타냅니다. */
class AwsModulithInboundLoopRiskException internal constructor() :
    AwsModulithEventException(AwsModulithDiagnosticCode.INBOUND, AwsModulithFailurePhase.DECODE)

/** idempotency claim capacity가 부족합니다. */
class AwsModulithClaimCapacityException internal constructor() :
    AwsModulithEventException(AwsModulithDiagnosticCode.CLAIM, AwsModulithFailurePhase.CLAIM)

/** 같은 event가 다른 handler에 의해 처리 중입니다. */
class AwsModulithEventInProgressException internal constructor() :
    AwsModulithEventException(AwsModulithDiagnosticCode.CLAIM, AwsModulithFailurePhase.CLAIM)

/** fencing token 또는 lease가 오래되었습니다. */
class AwsModulithStaleClaimException internal constructor() :
    AwsModulithEventException(AwsModulithDiagnosticCode.CLAIM, AwsModulithFailurePhase.CLAIM)

/** claim 상태 변경에 실패했습니다. */
class AwsModulithClaimMutationException internal constructor() :
    AwsModulithEventException(AwsModulithDiagnosticCode.CLAIM, AwsModulithFailurePhase.CLAIM)

/** local dispatch가 실패했습니다. */
class AwsModulithDispatchException internal constructor() :
    AwsModulithEventException(AwsModulithDiagnosticCode.DISPATCH_ACK, AwsModulithFailurePhase.DISPATCH)

/** SQS acknowledgement가 실패했습니다. */
class AwsModulithAcknowledgementException internal constructor() :
    AwsModulithEventException(AwsModulithDiagnosticCode.DISPATCH_ACK, AwsModulithFailurePhase.ACK)

/** cleanup 실패를 bounded suppressed exception으로 보관하는 내부 타입입니다. */
internal class AwsModulithCleanupException internal constructor() :
    AwsModulithEventException(AwsModulithDiagnosticCode.DISPATCH_ACK, AwsModulithFailurePhase.CLEANUP)
