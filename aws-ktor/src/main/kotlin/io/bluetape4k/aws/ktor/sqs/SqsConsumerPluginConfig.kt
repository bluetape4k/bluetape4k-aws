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
 * Ktor SQS consumer plugin 설정입니다.
 *
 * 계약:
 * - [queueUrl]과 [queueName] 중 정확히 하나만 설정해야 합니다.
 * - [sqsAsyncClient]는 application이 주입한 client이므로 plugin이 닫지 않습니다.
 * - plugin instance 하나는 handler 하나만 가집니다. 여러 queue가 필요하면 이후 registry-style
 *   integration에서 plugin instance를 추가로 설치합니다.
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

    /** application이 소유하는 AWS SDK v2 async SQS client입니다. */
    var sqsAsyncClient: SqsAsyncClient? = null

    /** plugin이 client를 생성할 때 사용할 선택적 SQS region입니다. */
    var region: String? = null

    /** plugin이 client를 생성할 때 사용할 선택적 SQS endpoint override입니다. */
    var endpointOverride: URI? = null

    /** plugin이 client를 생성할 때 사용할 선택적 credentials provider입니다. */
    var credentialsProvider: AwsCredentialsProvider? = null

    /** 소비할 queue URL입니다. [queueName]과 동시에 설정할 수 없습니다. */
    var queueUrl: String? = null

    /** polling 전에 SQS로 확인할 queue name입니다. [queueUrl]과 동시에 설정할 수 없습니다. */
    var queueName: String? = null

    /** 동시에 실행할 poller coroutine 수입니다. */
    var coroutines: Int = 1

    /** receive 호출당 가져올 최대 메시지 수입니다. AWS SQS는 `1..10`을 허용합니다. */
    var maxMessages: Int = 10

    /** long-poll 대기 시간 초 단위 값입니다. AWS SQS는 `0..20`을 허용합니다. */
    var waitTimeSeconds: Int = 20

    /** receive 요청에 적용할 선택적 visibility timeout 초 단위 값입니다. */
    var visibilityTimeoutSeconds: Int? = null

    /** handler가 정상 반환한 뒤 메시지를 삭제할지 여부입니다. */
    var deleteOnSuccess: Boolean = true

    /** 실패 후 적용할 선택적 visibility timeout입니다. 즉시 재전달하려면 `0`을 사용합니다. */
    var failureVisibilityTimeoutSeconds: Int? = null

    /** 선택적 수동 dead-letter queue URL입니다. 가능하면 native SQS redrive를 우선 사용합니다. */
    var deadLetterQueueUrl: String? = null

    /** 선택적 수동 dead-letter queue name입니다. [deadLetterQueueUrl]과 동시에 설정할 수 없습니다. */
    var deadLetterQueueName: String? = null

    /** graceful shutdown 중 처리 중인 handler를 취소하기 전에 기다릴 timeout입니다. */
    var shutdownTimeout: Duration = Duration.ofSeconds(30)

    /** 일시적인 SQS 실패에 대해 receive loop가 사용할 backoff 정책입니다. */
    var pollBackoff: SqsPollBackoff = SqsPollBackoff()

    /** handler 실행 중 visibility를 연장하는 선택적 heartbeat 간격입니다. */
    var visibilityHeartbeatSeconds: Int? = null

    /** poller와 handler가 사용할 선택적 dispatcher입니다. 기본값은 [coroutines]로 제한한 Dispatchers.IO입니다. */
    var dispatcher: CoroutineDispatcher? = null

    /** [onMessage] handler에 전달할 AWS SQS 메시지를 역직렬화하는 converter입니다. */
    var converter: SqsMessageConverter = StringOrByteArraySqsMessageConverter

    /** handler 호출 전에 변환이 실패했을 때 사용할 정책입니다. */
    var conversionFailurePolicy: SqsConversionFailurePolicy = SqsConversionFailurePolicy.HandleAsFailure

    /** 변환 또는 handler 실패 후 visibility를 선택하는 선택적 전략입니다. */
    var failureVisibilityStrategy: SqsFailureVisibilityStrategy? = null

    private val interceptors = mutableListOf<SqsConsumerInterceptor>()
    private val observers = mutableListOf<SqsConsumerObserver>()
    private var messageType: KClass<out Any>? = null
    private var messageHandler: (suspend SqsMessageContext.(Any) -> Unit)? = null
    private val clientCustomizers = mutableListOf<AwsKtorSqsAsyncClientCustomizer>()

    /**
     * plugin이 생성하는 SQS async client builder에 customization을 추가합니다.
     */
    fun sqsAsyncClient(customizer: AwsKtorSqsAsyncClientCustomizer) {
        clientCustomizers += customizer
    }

    /**
     * receive, invoke, ack, nack hook을 처리할 runtime interceptor를 추가합니다.
     */
    fun interceptor(interceptor: SqsConsumerInterceptor) {
        interceptors += interceptor
    }

    /**
     * runtime event를 Micrometer 또는 tracing으로 전달할 observer를 추가합니다.
     */
    fun observer(observer: SqsConsumerObserver) {
        observers += observer
    }

    /**
     * 이 plugin instance에서 사용할 유일한 message handler를 등록합니다.
     */
    inline fun <reified T: Any> onMessage(
        noinline handler: suspend SqsMessageContext.(T) -> Unit,
    ) {
        onMessage(T::class, handler)
    }

    /**
     * 이 plugin instance에서 사용할 유일한 message handler를 등록합니다.
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

    internal fun toRuntimeConfig(
        defaults: AwsKtorDefaults = AwsKtorDefaults(),
        clientFactory: (AwsKtorDefaults) -> SqsAsyncClient = ::createSqsAsyncClient,
    ): SqsConsumerRuntimeConfig {
        val type = requireNotNull(messageType) { "onMessage handler must be configured." }
        val handler = requireNotNull(messageHandler) { "onMessage handler must be configured." }
        val injectedClient = sqsAsyncClient
        val client = injectedClient ?: clientFactory(defaults)

        try {
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
        } catch (e: Throwable) {
            if (injectedClient == null) {
                try {
                    client.close()
                } catch (closeError: Throwable) {
                    e.addSuppressed(closeError)
                }
            }
            throw e
        }
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
