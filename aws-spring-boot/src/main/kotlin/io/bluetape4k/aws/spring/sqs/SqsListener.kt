package io.bluetape4k.aws.spring.sqs

/**
 * Marks a method that receives SQS queue messages.
 *
 * - [queue] is a queue name, queue URL, or `${...}` placeholder. SpEL is not supported.
 * - [maxMessages], [waitTimeSeconds], [visibilityTimeoutSeconds],
 *   When [errorVisibilityTimeoutSeconds] is negative, the global listener setting is used.
 * - Listener methods may accept one of `String`, AWS SDK [software.amazon.awssdk.services.sqs.model.Message],
 *   or [SqsReceivedMessage] as their argument.
 *
 * ```kotlin
 * import org.springframework.stereotype.Component
 *
 * @Component
 * class OrderListener {
 *
 *     @SqsListener(queue = "orders", maxMessages = 10, waitTimeSeconds = 20)
 *     suspend fun handle(message: SqsReceivedMessage) {
 *         check(message.body.isNotBlank())
 *     }
 * }
 * ```
 *
 * `queue = "orders"` resolves to that URL when `bluetape4k.aws.sqs.queues.orders.url` is configured,
 * and otherwise resolves the queue URL with an SQS `GetQueueUrl` request.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class SqsListener(
    val queue: String,
    val id: String = "",
    val maxMessages: Int = -1,
    val waitTimeSeconds: Int = -1,
    val visibilityTimeoutSeconds: Int = -1,
    val errorVisibilityTimeoutSeconds: Int = -1,
    val autoStartup: Boolean = true,
)
