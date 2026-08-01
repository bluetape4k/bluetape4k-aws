package io.bluetape4k.aws.spring.sqs

/**
 * SQS 리스너의 수신, 핸들러, 확인 단계를 가로챕니다.
 *
 * 리스너 컨테이너를 특정 관찰 가능성 라이브러리에 결합하지 않고 메트릭, 추적, 구조화된 로깅,
 * 정책 검사를 추가하려면 이 인터페이스를 구현하세요.
 */
interface SqsListenerInterceptor {

    suspend fun beforeReceive(listenerId: String, queueUrl: String) {
    }

    suspend fun afterReceive(listenerId: String, queueUrl: String, messages: List<SqsReceivedMessage>, error: Throwable?) {
    }

    suspend fun beforeHandle(context: SqsListenerInvocationContext) {
    }

    suspend fun afterHandle(context: SqsListenerInvocationContext, error: Throwable?) {
    }

    suspend fun beforeAcknowledgement(context: SqsListenerInvocationContext, action: SqsAcknowledgementAction) {
    }

    suspend fun afterAcknowledgement(
        context: SqsListenerInvocationContext,
        action: SqsAcknowledgementAction,
        error: Throwable?,
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
