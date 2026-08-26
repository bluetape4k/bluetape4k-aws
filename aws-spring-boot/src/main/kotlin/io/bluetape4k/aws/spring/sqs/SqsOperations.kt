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
@Suppress("TooManyFunctions")
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
     * queue URL의 속성을 조회합니다. 기본 구현은 기존 사용자 구현과의 ABI 호환을 위해 빈 결과를 반환합니다.
     * FIFO/visibility cache를 사용하려면 AWS adapter 또는 custom 구현이 이 메서드를 재정의해야 합니다.
     */
    suspend fun getQueueAttributes(
        queueUrl: String,
        attributeNames: Collection<QueueAttributeName>,
    ): Map<QueueAttributeName, String> = emptyMap()

    /**
     * 큐 URL로 메시지를 전송합니다.
     */
    suspend fun send(
        queueUrl: String,
        body: String,
        delaySeconds: Int? = null,
    ): SendMessageResponse

    /**
     * SQS 큐 URL로 메시지를 전송합니다.
     *
     * FIFO 큐는 [SqsSendRequest.messageGroupId]와 [SqsSendRequest.messageDeduplicationId]를 사용합니다.
     *
     * 기본 구현은 [send]에 위임해 기존 [SqsOperations] 구현과의 호환성을 유지하므로 FIFO 필드와
     * 사용자 정의 [SqsSendRequest.messageAttributes]를 무시합니다. 모든 요청 필드를 유지하려면
     * 이 메서드를 재정의하세요.
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
     * 여러 메시지를 삭제합니다. 기존 구현체는 단건 삭제 fallback을 사용합니다.
     */
    suspend fun deleteBatch(
        queueUrl: String,
        receiptHandles: Collection<String>,
    ): SqsBatchDeleteResult {
        val handles = receiptHandles.toList()
        validateDeleteBatchHandles(handles)
        if (handles.isEmpty()) {
            return SqsBatchDeleteResult(emptyList(), emptyList())
        }
        val successful = handles.mapIndexed { index, handle ->
            delete(queueUrl, handle)
            "entry-$index"
        }
        return SqsBatchDeleteResult(successful, emptyList())
    }

    /**
     * 메시지 visibility timeout을 변경합니다.
     */
    suspend fun changeVisibility(
        queueUrl: String,
        receiptHandle: String,
        timeoutSeconds: Int,
    ): ChangeMessageVisibilityResponse

    /**
     * 여러 메시지의 visibility timeout을 변경합니다. 기존 구현체는 단건 fallback을 사용합니다.
     */
    suspend fun changeVisibilityBatch(
        queueUrl: String,
        requests: Collection<SqsChangeVisibilityRequest>,
    ): SqsBatchVisibilityResult {
        val batch = requests.toList()
        validateVisibilityBatchRequests(batch)
        if (batch.isEmpty()) {
            return SqsBatchVisibilityResult(emptyList(), emptyList())
        }
        val successful = batch.map {
            changeVisibility(queueUrl, it.receiptHandle, it.timeoutSeconds)
            it.messageId
        }
        return SqsBatchVisibilityResult(successful, emptyList())
    }

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

private fun validateDeleteBatchHandles(handles: List<String>) {
    requireBatchSize(handles.size)
    require(handles.distinct().size == handles.size) {
        "duplicate batch delete receipt handle"
    }
}

private fun validateVisibilityBatchRequests(requests: List<SqsChangeVisibilityRequest>) {
    requireBatchSize(requests.size, "batch visibility supports at most 10 messages")
    require(requests.map { it.messageId }.distinct().size == requests.size &&
        requests.map { it.receiptHandle }.distinct().size == requests.size) {
        "duplicate batch visibility request"
    }
    requests.forEach { requireVisibilityTimeout(it.timeoutSeconds) }
}
