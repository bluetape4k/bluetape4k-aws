package io.bluetape4k.aws.ktor.sns

import software.amazon.awssdk.services.sns.model.ConfirmSubscriptionResponse
import software.amazon.awssdk.services.sns.model.PublishResponse

/**
 * Ktor 애플리케이션을 위한 코루틴 기반 SNS 작업입니다.
 *
 * ## 계약
 *
 * 애플리케이션 코드에 `CompletableFuture`를 노출하지 않고 주제 생성, 주제 조회, 게시,
 * SMS 게시, 구독 확인 기능을 제공합니다.
 */
interface SnsKtorOperations {

    /** 표준 주제를 생성하고 topic ARN을 반환합니다. */
    suspend fun createTopic(
        topicName: String,
        attributes: Map<String, String> = emptyMap(),
    ): String

    /** FIFO 주제를 생성하고 topic ARN을 반환합니다. */
    suspend fun createFifoTopic(
        topicName: String,
        contentBasedDeduplication: Boolean = true,
        fifoThroughputScope: SnsFifoThroughputScope? = null,
        attributes: Map<String, String> = emptyMap(),
    ): String

    /** [SnsKtorPluginConfig.topics] 구성으로 주제를 생성합니다. */
    suspend fun createConfiguredTopic(topicName: String): String

    /**
     * 주제 이름으로 topic ARN을 찾습니다.
     *
     * 모든 `ListTopics` 페이지를 스캔하고 일치하는 주제가 없으면 null을 반환합니다.
     */
    suspend fun findTopicArn(topicName: String): String?

    /** SNS 주제에 메시지를 게시합니다. */
    suspend fun publish(request: SnsPublishRequest): PublishResponse

    /** 전화번호로 SMS 메시지를 직접 게시합니다. */
    suspend fun publishSms(request: SnsSmsRequest): PublishResponse

    /** 명시적인 topic ARN과 토큰으로 HTTP(S) 엔드포인트 구독을 확인합니다. */
    suspend fun confirmSubscription(
        topicArn: String,
        token: String,
        authenticateOnUnsubscribe: Boolean = true,
    ): ConfirmSubscriptionResponse

    /**
     * 호출자가 검증한 SNS 메시지로 HTTP(S) 엔드포인트 구독을 확인하거나 재확인합니다.
     */
    suspend fun confirmSubscription(
        message: TrustedSnsHttpMessage,
        authenticateOnUnsubscribe: Boolean = true,
    ): ConfirmSubscriptionResponse
}
