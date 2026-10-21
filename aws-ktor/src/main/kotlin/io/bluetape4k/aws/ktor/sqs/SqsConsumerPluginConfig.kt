package io.bluetape4k.aws.ktor.sqs

import aws.sdk.kotlin.services.sqs.endpoints.DefaultSqsEndpointProvider
import aws.sdk.kotlin.services.sqs.endpoints.SqsEndpointProvider
import io.bluetape4k.aws.ktor.AwsKotlinEndpointPolicyInterceptor
import io.bluetape4k.aws.ktor.checkedAwsKotlinEndpointProvider
import io.bluetape4k.aws.ktor.requireAwsKotlinEndpointProviderUnset
import io.bluetape4k.ToStringBuilder
import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorSqsClientCustomizer
import io.bluetape4k.aws.kotlin.sqs.sqsClientOf
import io.bluetape4k.javatimes.seconds
import aws.sdk.kotlin.services.sqs.SqsClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import kotlinx.coroutines.CoroutineDispatcher
import java.time.Duration
import kotlin.reflect.KClass

/**
 * Ktor SQS consumer plugin 설정입니다.
 *
 * 계약:
 * - [queueUrl]과 [queueName] 중 정확히 하나만 설정해야 합니다.
 * - [sqsClient]는 application이 주입한 client이므로 plugin이 닫지 않습니다.
 * - 주입된 client에는 플러그인의 endpoint 정책 검사가 적용되지 않으므로 호출자가 전송 endpoint를 보호해야 합니다.
 * - plugin instance 하나는 handler 하나만 가집니다. 여러 queue가 필요하면 이후 registry-style
 *   integration에서 plugin instance를 추가로 설치합니다.
 *
 * ```kotlin
 * install(SqsConsumer) {
 *     sqsClient = client
 *     queueName = "orders"
 *     coroutines = 4
 *     onMessage<String> { body -> process(body) }
 * }
 * ```
 */
class SqsConsumerPluginConfig {

    /** 애플리케이션이 소유하는 AWS Kotlin SDK SQS client입니다. */
    var sqsClient: SqsClient? = null

    /** plugin이 client를 생성할 때 사용할 선택적 SQS region입니다. */
    var region: String? = null

    /** plugin이 client를 생성할 때 사용할 선택적 SQS endpoint override입니다. */
    var endpointOverride: Url? = null

    /** 플러그인이 클라이언트를 생성할 때 사용할 선택적인 Smithy credentials provider입니다. */
    var credentialsProvider: CredentialsProvider? = null

    /** 플러그인이 생성할 클라이언트에 전달할 호출자 소유 Smithy HTTP 엔진입니다. */
    var httpClient: HttpClientEngine? = null

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
    var shutdownTimeout: Duration = 30.seconds()

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
    private val clientCustomizers = mutableListOf<AwsKtorSqsClientCustomizer>()

    /**
     * 플러그인이 생성하는 SQS 클라이언트 builder에 customization을 추가합니다.
     * 동적 endpoint provider는 허용되지 않습니다. 고정 endpoint에는 [endpointOverride]를 사용합니다.
     */
    fun sqsClient(customizer: AwsKtorSqsClientCustomizer) {
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
        clientFactory: (AwsKtorDefaults) -> SqsClient = ::createSqsClient,
    ): SqsConsumerRuntimeConfig {
        val type = requireNotNull(messageType) { "onMessage handler must be configured." }
        val handler = requireNotNull(messageHandler) { "onMessage handler must be configured." }
        val injectedClient = sqsClient
        val client = injectedClient ?: clientFactory(defaults)

        return runCatching {
            SqsConsumerRuntimeConfig(
                sqsClient = client,
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
        }.getOrElse { e ->
            if (injectedClient == null) {
                runCatching {
                    client.close()
                }.onFailure(e::addSuppressed)
            }
            throw e
        }
    }

    private fun createSqsClient(defaults: AwsKtorDefaults): SqsClient {
        val effectiveRegion = region?.takeIf { it.isNotBlank() } ?: defaults.region?.takeIf { it.isNotBlank() }
        val effectiveEndpoint = endpointOverride ?: defaults.endpointOverride?.let { Url.parse(it.toString()) }
        require(effectiveEndpoint == null || !effectiveRegion.isNullOrBlank()) {
            "region must be configured when endpointOverride is configured."
        }

        return sqsClientOf(
            endpointUrl = effectiveEndpoint,
            region = effectiveRegion,
            credentialsProvider = credentialsProvider ?: defaults.kotlinCredentialsProvider,
            httpClient = httpClient ?: defaults.kotlinHttpClient,
            builder = {
                defaults.sqsClientCustomizers.forEach { it.customize(this) }
                clientCustomizers.forEach { it.customize(this) }
                requireAwsKotlinEndpointProviderUnset(endpointProvider)
                endpointProvider = checkedAwsKotlinEndpointProvider(
                    provider = endpointProvider ?: DefaultSqsEndpointProvider(),
                    region = { it.region },
                    allowInsecureLocalHttpEndpoint = defaults.allowInsecureLocalHttpEndpoint,
                    create = { resolver -> SqsEndpointProvider { parameters -> resolver(parameters) } },
                )
                interceptors.add(
                    AwsKotlinEndpointPolicyInterceptor(
                        configuredEndpoint = this.endpointUrl?.toString(),
                        region = this.region,
                        allowInsecureLocalHttpEndpoint = defaults.allowInsecureLocalHttpEndpoint,
                    ),
                )
            },
        )
    }

    override fun toString(): String =
        ToStringBuilder(this)
            .add("sqsClientConfigured", sqsClient != null)
            .add("region", region)
            .add("endpointConfigured", endpointOverride != null)
            .add("credentialsProviderConfigured", credentialsProvider != null)
            .add("httpClientConfigured", httpClient != null)
            .add("queueUrlConfigured", queueUrl != null)
            .add("queueName", queueName)
            .add("coroutines", coroutines)
            .add("maxMessages", maxMessages)
            .add("waitTimeSeconds", waitTimeSeconds)
            .add("visibilityTimeoutSeconds", visibilityTimeoutSeconds)
            .add("deleteOnSuccess", deleteOnSuccess)
            .add("failureVisibilityTimeoutSeconds", failureVisibilityTimeoutSeconds)
            .add("deadLetterQueueUrlConfigured", deadLetterQueueUrl != null)
            .add("shutdownTimeout", shutdownTimeout)
            .add("pollBackoff", pollBackoff)
            .add("visibilityHeartbeatSeconds", visibilityHeartbeatSeconds)
            .add("dispatcherConfigured", dispatcher != null)
            .add("customConverter", converter !== StringOrByteArraySqsMessageConverter)
            .add("conversionFailurePolicy", conversionFailurePolicy)
            .add("failureVisibilityStrategyConfigured", failureVisibilityStrategy != null)
            .toString()
}
