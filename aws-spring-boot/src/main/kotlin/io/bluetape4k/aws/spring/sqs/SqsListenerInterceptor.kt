package io.bluetape4k.aws.spring.sqs

/**
 * SQS 리스너의 수신, 핸들러, 확인 단계를 가로챕니다.
 *
 * 리스너 컨테이너를 특정 관찰 가능성 라이브러리에 결합하지 않고 메트릭, 추적, 구조화된 로깅,
 * 정책 검사를 추가하려면 이 인터페이스를 구현하세요.
 */
@Suppress("TooManyFunctions")
interface SqsListenerInterceptor {

    suspend fun beforeReceive(listenerId: String, queueUrl: String) {
    }

    /**
     * batch 수신 직전 호출되는 correlation-aware hook입니다.
     * 기본 구현은 기존 단건 호환 hook으로 연결됩니다.
     */
    suspend fun beforeReceive(
        listenerId: String,
        queueUrl: String,
        correlation: SqsListenerBatchCorrelation,
    ) {
        beforeReceive(listenerId, queueUrl)
    }

    suspend fun afterReceive(listenerId: String, queueUrl: String, messages: List<SqsReceivedMessage>, error: Throwable?) {
    }

    /**
     * batch 수신 직후 호출되는 correlation-aware hook입니다.
     * 기본 구현은 기존 단건 호환 hook으로 연결됩니다.
     */
    suspend fun afterReceive(
        listenerId: String,
        queueUrl: String,
        messages: List<SqsReceivedMessage>,
        error: Throwable?,
        correlation: SqsListenerBatchCorrelation,
    ) {
        afterReceive(listenerId, queueUrl, messages, error)
    }

    suspend fun beforeHandle(context: SqsListenerInvocationContext) {
    }

    /** batch handler 관측을 위한 correlation-aware hook입니다. */
    suspend fun beforeBatchHandle(
        context: SqsListenerInvocationContext,
        correlation: SqsListenerBatchCorrelation,
        batchSize: Int,
    ) {
        beforeHandle(context)
    }

    suspend fun afterHandle(context: SqsListenerInvocationContext, error: Throwable?) {
    }

    /** batch handler 관측을 위한 correlation-aware hook입니다. */
    suspend fun afterBatchHandle(
        context: SqsListenerInvocationContext,
        error: Throwable?,
        correlation: SqsListenerBatchCorrelation,
        batchSize: Int,
    ) {
        afterHandle(context, error)
    }

    /** batch 재시도 1회를 알리는 hook입니다. */
    suspend fun onBatchRetry(
        context: SqsListenerInvocationContext,
        correlation: SqsListenerBatchCorrelation,
        batchSize: Int,
        attempt: Int,
        error: Throwable?,
    ) {
    }

    /** batch cancellation 1회를 알리는 hook입니다. */
    suspend fun onBatchCancellation(
        context: SqsListenerInvocationContext,
        correlation: SqsListenerBatchCorrelation,
        batchSize: Int,
    ) {
    }

    suspend fun beforeAcknowledgement(context: SqsListenerInvocationContext, action: SqsAcknowledgementAction) {
    }

    /**
     * batch acknowledgement 직전 호출되는 correlation-aware hook입니다.
     * [batchSize]는 현재 public acknowledgement 호출의 대상 크기입니다.
     */
    suspend fun beforeAcknowledgement(
        context: SqsListenerInvocationContext,
        action: SqsAcknowledgementAction,
        correlation: SqsListenerBatchCorrelation,
        batchSize: Int,
    ) {
        beforeAcknowledgement(context, action)
    }

    suspend fun afterAcknowledgement(
        context: SqsListenerInvocationContext,
        action: SqsAcknowledgementAction,
        error: Throwable?,
    ) {
    }

    /**
     * batch acknowledgement 직후 호출되는 correlation-aware hook입니다.
     * 기본 구현은 기존 단건 호환 hook으로 연결됩니다.
     */
    suspend fun afterAcknowledgement(
        context: SqsListenerInvocationContext,
        action: SqsAcknowledgementAction,
        error: Throwable?,
        correlation: SqsListenerBatchCorrelation,
        batchSize: Int,
    ) {
        afterAcknowledgement(context, action, error)
    }

    /** batch acknowledgement의 항목별 결과를 전달하는 hook입니다. */
    suspend fun onBatchAcknowledgementResult(
        context: SqsListenerInvocationContext,
        action: SqsAcknowledgementAction,
        result: SqsBatchAcknowledgementResult,
        correlation: SqsListenerBatchCorrelation,
        batchSize: Int,
    ) {
    }
}

/**
 * SQS 리스너 핸들러 호출 하나의 컨텍스트입니다.
 */
data class SqsListenerInvocationContext(
    val listenerId: String,
    val queueUrl: String,
    val message: SqsReceivedMessage,
    val attempt: Int,
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID: Long = -5671325956177467194L
    }
}

/**
 * SQS 메시지에 수행 중인 확인 작업입니다.
 */
enum class SqsAcknowledgementAction {
    ACK,
    NACK,
    CHANGE_VISIBILITY,
}
