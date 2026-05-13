package io.bluetape4k.aws.ktor.sqs

import kotlinx.coroutines.CoroutineDispatcher
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import java.time.Duration
import kotlin.reflect.KClass

/**
 * Configuration for the Ktor SQS consumer plugin.
 *
 * Contract:
 * - Exactly one of [queueUrl] and [queueName] must be set.
 * - [sqsAsyncClient] is injected by the application and is never closed by the plugin.
 * - One plugin instance has one handler. Install another plugin instance in a
 *   future registry-style integration when multiple queues are required.
 *
 * ```kotlin
 * install(SqsConsumer) {
 *     sqsAsyncClient = client
 *     queueName = "orders"
 *     coroutines = 4
 *     onMessage<String> { body -> process(body) }
 * }
 * ```
 */
class SqsConsumerPluginConfig {

    /** AWS SDK v2 async SQS client owned by the application. */
    var sqsAsyncClient: SqsAsyncClient? = null

    /** Queue URL to consume from. Mutually exclusive with [queueName]. */
    var queueUrl: String? = null

    /** Queue name resolved through SQS before polling. Mutually exclusive with [queueUrl]. */
    var queueName: String? = null

    /** Number of concurrent poller coroutines. */
    var coroutines: Int = 1

    /** Maximum messages per receive call. AWS SQS allows 1..10. */
    var maxMessages: Int = 10

    /** Long-poll wait time in seconds. AWS SQS allows 0..20. */
    var waitTimeSeconds: Int = 20

    /** Optional receive visibility timeout in seconds. */
    var visibilityTimeoutSeconds: Int? = null

    /** Deletes a message after the handler returns successfully. */
    var deleteOnSuccess: Boolean = true

    /** Optional failure visibility timeout. Use 0 for immediate redelivery. */
    var failureVisibilityTimeoutSeconds: Int? = null

    /** Optional manual dead-letter queue URL. Prefer native SQS redrive when possible. */
    var deadLetterQueueUrl: String? = null

    /** Optional manual dead-letter queue name. Mutually exclusive with [deadLetterQueueUrl]. */
    var deadLetterQueueName: String? = null

    /** Timeout used during graceful shutdown before in-flight handlers are cancelled. */
    var shutdownTimeout: Duration = Duration.ofSeconds(30)

    /** Receive-loop error backoff for transient SQS failures. */
    var pollBackoff: SqsPollBackoff = SqsPollBackoff()

    /** Optional heartbeat interval that extends visibility while a handler is running. */
    var visibilityHeartbeatSeconds: Int? = null

    /** Optional dispatcher for pollers and handlers. Defaults to Dispatchers.IO limited to [coroutines]. */
    var dispatcher: CoroutineDispatcher? = null

    /** Converter used to deserialize AWS SQS messages for [onMessage] handlers. */
    var converter: SqsMessageConverter = StringOrByteArraySqsMessageConverter

    private var messageType: KClass<out Any>? = null
    private var messageHandler: (suspend SqsMessageContext.(Any) -> Unit)? = null

    /**
     * Registers the only message handler for this plugin instance.
     */
    inline fun <reified T: Any> onMessage(
        noinline handler: suspend SqsMessageContext.(T) -> Unit,
    ) {
        onMessage(T::class, handler)
    }

    /**
     * Registers the only message handler for this plugin instance.
     */
    fun <T: Any> onMessage(
        type: KClass<T>,
        handler: suspend SqsMessageContext.(T) -> Unit,
    ) {
        require(messageHandler == null) { "Only one SQS message handler can be registered per plugin instance." }
        messageType = type
        messageHandler = {
            @Suppress("UNCHECKED_CAST")
            handler(it as T)
        }
    }

    internal fun toRuntimeConfig(): SqsConsumerRuntimeConfig {
        val client = requireNotNull(sqsAsyncClient) { "sqsAsyncClient must be configured." }
        val type = requireNotNull(messageType) { "onMessage handler must be configured." }
        val handler = requireNotNull(messageHandler) { "onMessage handler must be configured." }

        return SqsConsumerRuntimeConfig(
            sqsAsyncClient = client,
            queueUrl = queueUrl,
            queueName = queueName,
            coroutines = coroutines,
            maxMessages = maxMessages,
            waitTimeSeconds = waitTimeSeconds,
            visibilityTimeoutSeconds = visibilityTimeoutSeconds,
            deleteOnSuccess = deleteOnSuccess,
            failureVisibilityTimeoutSeconds = failureVisibilityTimeoutSeconds,
            deadLetterQueueUrl = deadLetterQueueUrl,
            deadLetterQueueName = deadLetterQueueName,
            shutdownTimeout = shutdownTimeout,
            pollBackoff = pollBackoff,
            visibilityHeartbeatSeconds = visibilityHeartbeatSeconds,
            dispatcher = dispatcher,
            converter = converter,
            messageType = type,
            messageHandler = handler,
        )
    }
}
