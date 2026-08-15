package io.bluetape4k.aws.spring.sns

import kotlinx.coroutines.CancellationException
import software.amazon.awssdk.services.sns.model.PublishResponse
import software.amazon.awssdk.services.sns.model.ConfirmSubscriptionResponse

/**
 * Spring 애플리케이션을 위한 코루틴 기반 SNS 작업입니다.
 *
 * ## 계약
 *
 * 애플리케이션 코드에 `CompletableFuture`를 노출하지 않고 주제 생성, 구성된 주제 생성,
 * 주제 조회, 메시지 게시 기능을 제공합니다.
 *
 * ```kotlin
 * class OrderTopic(private val sns: SnsOperations) {
 *     suspend fun publish(orderJson: String, topicArn: String) {
 *         sns.publish(SnsPublishRequest(topicArn = topicArn, message = orderJson))
 *     }
 * }
 * ```
 */
interface SnsOperations {

    /**
     * 표준 주제를 생성하고 topic ARN을 반환합니다.
     */
    suspend fun createTopic(
        topicName: String,
        attributes: Map<String, String> = emptyMap(),
    ): String

    /**
     * FIFO 주제를 생성하고 topic ARN을 반환합니다.
     */
    suspend fun createFifoTopic(
        topicName: String,
        contentBasedDeduplication: Boolean = true,
        fifoThroughputScope: SnsFifoThroughputScope? = null,
        attributes: Map<String, String> = emptyMap(),
    ): String

    /**
     * `bluetape4k.aws.sns.topics` 구성으로 주제를 생성합니다.
     */
    suspend fun createConfiguredTopic(topicName: String): String

    /**
     * 주제 이름으로 topic ARN을 찾습니다.
     *
     * 모든 `ListTopics` 페이지를 스캔하고 일치하는 주제가 없으면 null을 반환합니다.
     */
    suspend fun findTopicArn(topicName: String): String?

    /**
     * SNS 주제에 메시지를 게시합니다.
     */
    suspend fun publish(request: SnsPublishRequest): PublishResponse

    /**
     * SNS 배치 발행을 수행합니다.
     *
     * 기존 구현체는 단건 [publish]를 순차 호출하는 호환 fallback을 사용합니다.
     * 이 경로는 원자적 batch가 아니며 첫 실패에서 중단하고 자동 재시도하지 않습니다.
     */
    suspend fun publishBatch(
        request: SnsPublishBatchRequest,
        options: SnsBatchExecutionOptions = SnsBatchExecutionOptions(),
    ): SnsPublishBatchResult {
        // Legacy implementations intentionally remain sequential regardless of this option.
        require(options.maxInFlightBatches > 0) { "maxInFlightBatches must be positive." }
        if (request.entries.isEmpty()) {
            return SnsPublishBatchResult(emptyList(), emptyList())
        }

        val successful = mutableListOf<SnsPublishBatchSuccess>()
        request.entries.forEach { entry ->
            try {
                val response = publish(
                    SnsPublishRequest(
                        topicArn = request.topicArn,
                        message = entry.message,
                        subject = entry.subject,
                        messageAttributes = entry.messageAttributes,
                        messageGroupId = entry.messageGroupId,
                        messageDeduplicationId = entry.messageDeduplicationId,
                    )
                )
                successful += SnsPublishBatchSuccess(
                    entryId = entry.id,
                    messageId = response.messageId(),
                )
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                throw SnsBatchTransportException.from(cause, successful.map { it.entryId })
            }
        }
        return SnsPublishBatchResult(successful, emptyList())
    }

    /**
     * 전화번호로 SMS 메시지를 직접 게시합니다.
     */
    suspend fun publishSms(request: SnsSmsRequest): PublishResponse

    /**
     * SNS 확인 토큰으로 HTTP(S) 엔드포인트 구독을 확인합니다.
     */
    suspend fun confirmSubscription(
        topicArn: String,
        token: String,
        authenticateOnUnsubscribe: Boolean = true,
    ): ConfirmSubscriptionResponse

    /**
     * 파싱된 SNS 확인 메시지로 HTTP(S) 엔드포인트 구독을 확인하거나 재확인합니다.
     */
    suspend fun confirmSubscription(
        message: SnsHttpMessage,
        authenticateOnUnsubscribe: Boolean = true,
    ): ConfirmSubscriptionResponse
}
