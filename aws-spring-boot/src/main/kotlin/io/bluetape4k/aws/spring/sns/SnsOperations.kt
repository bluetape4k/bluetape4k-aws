package io.bluetape4k.aws.spring.sns

import software.amazon.awssdk.services.sns.model.PublishResponse

/**
 * Spring 애플리케이션에서 사용하는 Coroutines 기반 SNS 작업 계약.
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
     * 표준 topic을 생성하고 topic ARN을 반환합니다.
     */
    suspend fun createTopic(
        topicName: String,
        attributes: Map<String, String> = emptyMap(),
    ): String

    /**
     * FIFO topic을 생성하고 topic ARN을 반환합니다.
     */
    suspend fun createFifoTopic(
        topicName: String,
        contentBasedDeduplication: Boolean = true,
        fifoThroughputScope: SnsFifoThroughputScope? = null,
        attributes: Map<String, String> = emptyMap(),
    ): String

    /**
     * `bluetape4k.aws.sns.topics` 설정을 적용해 topic을 생성합니다.
     */
    suspend fun createConfiguredTopic(topicName: String): String

    /**
     * topic 이름으로 topic ARN을 조회합니다.
     *
     * 모든 `ListTopics` 페이지를 순회한 뒤 없으면 null을 반환합니다.
     */
    suspend fun findTopicArn(topicName: String): String?

    /**
     * SNS topic으로 메시지를 발행합니다.
     */
    suspend fun publish(request: SnsPublishRequest): PublishResponse
}
