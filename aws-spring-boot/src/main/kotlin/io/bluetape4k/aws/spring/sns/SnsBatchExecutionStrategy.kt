package io.bluetape4k.aws.spring.sns

/**
 * SNS batch 실행에서 strategy가 사용할 수 있는 library-owned 전송 경계입니다.
 *
 * 이 포트는 raw AWS client, credential, retry 정책 또는 client lifecycle을 노출하지 않습니다.
 * 호출자는 각 chunk를 한 번만 전송하고 typed 결과를 반환해야 하며, template이 포트를 닫을 때까지
 * 호출 범위를 구조화된 coroutine scope 안에 유지해야 합니다.
 */
public interface SnsBatchExecutionPort {

    /**
     * 최대 10개의 이미 검증된 항목을 SNS에 전송합니다.
     *
     * 구현체는 입력 list와 반환 결과를 변경하지 않아야 하며, 취소 예외를 다른 예외로 바꾸지 않아야
     * 합니다. 이 메서드가 반환된 뒤에는 strategy가 같은 항목을 재호출해서는 안 됩니다.
     */
    public suspend fun publishChunk(entries: List<SnsPublishBatchEntry>): SnsPublishBatchResult
}

/**
 * SNS batch 요청의 분할·동시성 정책을 주입하는 확장 지점입니다.
 *
 * 하나의 Spring singleton strategy 인스턴스가 여러 coroutine에서 동시에 호출될 수 있으므로
 * 구현체는 stateless이거나 thread-safe해야 합니다. request-local claim과 completed metadata는
 * 호출 사이에 공유되지 않습니다. strategy가 전송 이후 실패하면 원격 partial publish 상태가
 * 불확실할 수 있으므로 호출자는 불확실한 전체 요청을 자동 재생하지 않아야 합니다.
 */
public fun interface SnsBatchExecutionStrategy {

    /**
     * typed SNS batch 요청을 실행합니다.
     *
     * strategy는 raw [software.amazon.awssdk.services.sns.SnsAsyncClient] 대신 guarded port만
     * 사용해야 하며, [SnsBatchExecutionPort.publishChunk] 반환 결과를 요청 ID 집합과 일치시켜야 합니다.
     */
    public suspend fun execute(
        request: SnsPublishBatchRequest,
        options: SnsBatchExecutionOptions,
        port: SnsBatchExecutionPort,
    ): SnsPublishBatchResult
}

/** strategy와 library-owned port 사이의 계약 위반 유형입니다. */
public enum class SnsBatchExecutionContractError {
    /** chunk가 1..10 범위를 벗어났습니다. */
    INVALID_CHUNK,

    /** 한 invocation에서 같은 entry ID를 두 번 claim했습니다. */
    DUPLICATE_CLAIM,

    /** 설정된 동시 전송 수를 초과해 claim을 시도했습니다. */
    TOO_MANY_IN_FLIGHT,

    /** strategy가 요청 ID 집합과 일치하지 않는 결과를 반환했습니다. */
    INVALID_RESULT,

    /** strategy가 전송 이후 예상하지 못한 실패를 발생시켰습니다. */
    STRATEGY_FAILURE,

    /** template이 닫힌 뒤 port를 호출했습니다. */
    PORT_CLOSED,

    /** template 종료 시 claim 또는 SDK future가 남았습니다. */
    OUTSTANDING_CLAIM,
}

/**
 * SNS batch 실행 계약 위반을 민감한 원인 없이 전달합니다.
 *
 * 원본 cause, payload, ARN, credential은 보관하거나 문자열에 포함하지 않습니다. 취소·transport·
 * protocol 예외는 별도의 기존 타입 의미를 유지합니다.
 */
public class SnsBatchExecutionContractException(
    /** 계약 위반의 안정적인 분류입니다. */
    public val error: SnsBatchExecutionContractError,
) : IllegalStateException("SNS batch execution contract failed: error=$error")

/** 기본 strategy: guarded port를 bounded coordinator에 연결합니다. */
internal object DefaultSnsBatchExecutionStrategy : SnsBatchExecutionStrategy {

    override suspend fun execute(
        request: SnsPublishBatchRequest,
        options: SnsBatchExecutionOptions,
        port: SnsBatchExecutionPort,
    ): SnsPublishBatchResult =
        SnsBatchExecutionCoordinator(
            publishChunk = { _, entries -> port.publishChunk(entries) },
            mapChunk = SnsBatchResponseMapper::map,
        ).execute(request, options)
}
