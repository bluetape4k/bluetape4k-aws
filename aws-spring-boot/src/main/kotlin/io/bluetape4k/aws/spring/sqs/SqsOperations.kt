package io.bluetape4k.aws.spring.sqs

import kotlinx.coroutines.flow.Flow
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityResponse
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import software.amazon.awssdk.services.sqs.model.SendMessageResponse

/**
 * Coroutine-based SQS operations contract for Spring applications.
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
     * Resolves a queue URL by queue name.
     */
    suspend fun getQueueUrl(queueName: String): String

    /**
     * Creates a queue with the specified attributes and returns its URL.
     */
    suspend fun createQueue(
        queueName: String,
        attributes: Map<QueueAttributeName, String> = emptyMap(),
    ): String

    /**
     * Creates queues from `bluetape4k.aws.sqs.queues` configuration.
     *
     * Currently converts the `redrivePolicy` setting into the `RedrivePolicy` attribute.
     */
    suspend fun createConfiguredQueue(queueName: String): String

    /**
     * Sends a message to a queue URL.
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
     * Receives messages from a queue in a batch.
     */
    suspend fun receive(
        queueUrl: String,
        maxMessages: Int = 10,
        waitTimeSeconds: Int = 20,
        visibilityTimeoutSeconds: Int? = null,
    ): List<SqsReceivedMessage>

    /**
     * Deletes a processed message from the queue.
     */
    suspend fun delete(
        queueUrl: String,
        receiptHandle: String,
    ): DeleteMessageResponse

    /**
     * Changes the message visibility timeout.
     */
    suspend fun changeVisibility(
        queueUrl: String,
        receiptHandle: String,
        timeoutSeconds: Int,
    ): ChangeMessageVisibilityResponse

    /**
     * Provides queue receive results as a cold infinite [Flow].
     *
     * The caller must explicitly delete messages.
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
