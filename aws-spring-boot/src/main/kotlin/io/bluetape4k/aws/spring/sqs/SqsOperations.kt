package io.bluetape4k.aws.spring.sqs

import kotlinx.coroutines.flow.Flow
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityResponse
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import software.amazon.awssdk.services.sqs.model.SendMessageResponse

/**
 * Spring 애플리케이션에서 사용하는 Coroutines 기반 SQS 작업 계약.
 *
 * ```kotlin
 * import kotlinx.coroutines.CancellationException
 *
 * class OrderQueue(private val sqs: SqsOperations) {
 *
 *     suspend fun publish(orderJson: String) {
 *         val queueUrl = sqs.getQueueUrl("orders")
 *         sqs.send(queueUrl, orderJson)
 *     }
 *
 *     suspend fun processOnce() {
 *         val queueUrl = sqs.getQueueUrl("orders")
 *         sqs.receive(queueUrl, maxMessages = 10).forEach { message ->
 *             try {
 *                 check(message.body.isNotBlank())
 *                 sqs.delete(queueUrl, message.receiptHandle)
 *             } catch (e: CancellationException) {
 *                 throw e
 *             } catch (e: Exception) {
 *                 sqs.changeVisibility(queueUrl, message.receiptHandle, timeoutSeconds = 0)
 *             }
 *         }
 *     }
 * }
 * ```
 */
interface SqsOperations {

    /**
     * 큐 이름으로 큐 URL을 조회합니다.
     */
    suspend fun getQueueUrl(queueName: String): String

    /**
     * 지정한 속성으로 큐를 생성하고 URL을 반환합니다.
     */
    suspend fun createQueue(
        queueName: String,
        attributes: Map<QueueAttributeName, String> = emptyMap(),
    ): String

    /**
     * `bluetape4k.aws.sqs.queues` 설정을 적용해 큐를 생성합니다.
     *
     * 현재는 `redrivePolicy` 설정을 `RedrivePolicy` 속성으로 변환해 적용합니다.
     */
    suspend fun createConfiguredQueue(queueName: String): String

    /**
     * 큐 URL로 메시지를 전송합니다.
     */
    suspend fun send(
        queueUrl: String,
        body: String,
        delaySeconds: Int? = null,
    ): SendMessageResponse

    /**
     * Sends a message to an SQS queue URL.
     *
     * FIFO queues use [SqsSendRequest.messageGroupId] and
     * [SqsSendRequest.messageDeduplicationId].
     *
     * The default implementation preserves compatibility with existing
     * [SqsOperations] implementations by delegating to [send] and therefore
     * ignores FIFO fields and custom [SqsSendRequest.messageAttributes].
     * Override this method to preserve every request field.
     */
    suspend fun send(request: SqsSendRequest): SendMessageResponse =
        send(request.queueUrl, request.body, request.delaySeconds)

    /**
     * 큐에서 메시지를 배치로 수신합니다.
     */
    suspend fun receive(
        queueUrl: String,
        maxMessages: Int = 10,
        waitTimeSeconds: Int = 20,
        visibilityTimeoutSeconds: Int? = null,
    ): List<SqsReceivedMessage>

    /**
     * 처리 완료된 메시지를 큐에서 삭제합니다.
     */
    suspend fun delete(
        queueUrl: String,
        receiptHandle: String,
    ): DeleteMessageResponse

    /**
     * 메시지 visibility timeout을 변경합니다.
     */
    suspend fun changeVisibility(
        queueUrl: String,
        receiptHandle: String,
        timeoutSeconds: Int,
    ): ChangeMessageVisibilityResponse

    /**
     * 큐 수신 결과를 차가운 무한 [Flow]로 제공합니다.
     *
     * 메시지 삭제는 호출자가 명시적으로 수행해야 합니다.
     *
     * ```kotlin
     * suspend fun consume(sqs: SqsOperations, queueUrl: String) {
     *     sqs.receiveFlow(queueUrl, maxMessages = 5)
     *         .collect { message ->
     *             check(message.body.isNotBlank())
     *             sqs.delete(message.queueUrl, message.receiptHandle)
     *         }
     * }
     * ```
     */
    fun receiveFlow(
        queueUrl: String,
        maxMessages: Int = 10,
        waitTimeSeconds: Int = 20,
        visibilityTimeoutSeconds: Int? = null,
    ): Flow<SqsReceivedMessage>
}
