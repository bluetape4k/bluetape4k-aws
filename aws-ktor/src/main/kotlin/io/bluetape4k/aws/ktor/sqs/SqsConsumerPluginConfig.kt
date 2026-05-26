package io.bluetape4k.aws.ktor.sqs

import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorSqsAsyncClientCustomizer
import kotlinx.coroutines.CoroutineDispatcher
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import java.net.URI
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

    /** Optional SQS region used when the plugin creates the client. */
    var region: String? = null

    /** Optional SQS endpoint override used when the plugin creates the client. */
    var endpointOverride: URI? = null

    /** Optional credentials provider used when the plugin creates the client. */
    var credentialsProvider: AwsCredentialsProvider? = null

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

    /** Policy used when conversion fails before the handler is invoked. */
    var conversionFailurePolicy: SqsConversionFailurePolicy = SqsConversionFailurePolicy.HandleAsFailure

    /** Optional strategy for choosing visibility after conversion or handler failure. */
    var failureVisibilityStrategy: SqsFailureVisibilityStrategy? = null

    private val interceptors = mutableListOf<SqsConsumerInterceptor>()
    private val observers = mutableListOf<SqsConsumerObserver>()
    private var messageType: KClass<out Any>? = null
    private var messageHandler: (suspend SqsMessageContext.(Any) -> Unit)? = null
    private val clientCustomizers = mutableListOf<AwsKtorSqsAsyncClientCustomizer>()

    /**
     * Adds SQS async client builder customization for plugin-created clients.
     */
    fun sqsAsyncClient(customizer: AwsKtorSqsAsyncClientCustomizer) {
        clientCustomizers += customizer
    }

    /**
     * Adds a runtime interceptor for receive, invoke, ack, and nack hooks.
     */
    fun interceptor(interceptor: SqsConsumerInterceptor) {
        interceptors += interceptor
    }

    /**
     * Adds an observer that can bridge runtime events to Micrometer or tracing.
     */
    fun observer(observer: SqsConsumerObserver) {
        observers += observer
    }

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

    internal fun toRuntimeConfig(defaults: AwsKtorDefaults = AwsKtorDefaults()): SqsConsumerRuntimeConfig {
        val injectedClient = sqsAsyncClient
        val client = injectedClient ?: createSqsAsyncClient(defaults)
        val type = requireNotNull(messageType) { "onMessage handler must be configured." }
        val handler = requireNotNull(messageHandler) { "onMessage handler must be configured." }

        return SqsConsumerRuntimeConfig(
            sqsAsyncClient = client,
            ownsClient = injectedClient == null,
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
            conversionFailurePolicy = conversionFailurePolicy,
            failureVisibilityStrategy = failureVisibilityStrategy,
            interceptors = interceptors.toList(),
            observers = observers.toList(),
            messageType = type,
            messageHandler = handler,
        )
    }

    private fun createSqsAsyncClient(defaults: AwsKtorDefaults): SqsAsyncClient {
        val effectiveRegion = region?.takeIf { it.isNotBlank() } ?: defaults.region?.takeIf { it.isNotBlank() }
        val effectiveEndpoint = endpointOverride ?: defaults.javaEndpointOverride
        require(effectiveEndpoint == null || !effectiveRegion.isNullOrBlank()) {
            "region must be configured when endpointOverride is configured."
        }

        val builder = SqsAsyncClient.builder()
        effectiveRegion?.let { builder.region(Region.of(it)) }
        (credentialsProvider ?: defaults.javaCredentialsProvider)?.let(builder::credentialsProvider)
        effectiveEndpoint?.let(builder::endpointOverride)
        defaults.sqsAsyncClientCustomizers.forEach { it.customize(builder) }
        clientCustomizers.forEach { it.customize(builder) }

        return builder.build()
    }
}
