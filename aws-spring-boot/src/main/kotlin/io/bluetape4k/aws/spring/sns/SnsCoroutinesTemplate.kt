package io.bluetape4k.aws.spring.sns

import kotlinx.coroutines.future.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.model.ConfirmSubscriptionResponse
import software.amazon.awssdk.services.sns.model.PublishResponse

/**
 * AWS SDK v2 [SnsAsyncClient]를 사용하는 코루틴 친화적인 [SnsOperations] 구현입니다.
 *
 * ## 계약
 *
 * `CompletableFuture` SNS API를 suspend 함수로 감싸고 [SnsProperties]의 구성된 주제 속성을
 * 적용합니다. 단건 API는 AWS SDK 예외를 그대로 전파하고, batch API는
 * [SnsBatchTransportException] 또는 [SnsBatchProtocolException]으로 안전하게 정규화합니다.
 *
 * ```kotlin
 * val topicArn = sns.createConfiguredTopic("orders")
 * sns.publish(SnsPublishRequest(topicArn = topicArn, message = orderJson))
 * ```
 *
 * Java 호출부에서 세 번째 인자로 `null`을 전달하면 strategy와 resolver
 * overload가 모호해질 수 있으므로, 명시적 cast를 사용하거나 4-인자 생성자를
 * 선택하세요.
 *
 * resolver를 직접 주입하지 않는 생성자는 `SnsAsyncClient`의 endpoint/region이
 * [SnsProperties]와 일치한다고 가정합니다. client identity가 다르거나 검사할 수
 * 없으면 resolver를 명시적으로 주입하는 생성자를 사용하세요.
 */
@Suppress("TooManyFunctions")
class SnsCoroutinesTemplate private constructor(
    private val snsAsyncClient: SnsAsyncClient,
    private val properties: SnsProperties,
    private val topicArnResolver: SnsTopicArnResolver,
    private val batchExecutionStrategy: SnsBatchExecutionStrategy,
    @Suppress("UNUSED_PARAMETER") private val constructorMarker: Unit,
): SnsOperations {

    /** 기존 SNS template 경로를 사용하는 기본 bounded strategy 생성자입니다. */
    constructor(
        snsAsyncClient: SnsAsyncClient,
        properties: SnsProperties,
    ) : this(
        snsAsyncClient,
        properties,
        defaultSnsTopicArnResolver(snsAsyncClient, properties),
        DefaultSnsBatchExecutionStrategy,
        Unit,
    )

    /** guarded typed port를 사용하는 명시적 SNS batch strategy 생성자입니다. */
    constructor(
        snsAsyncClient: SnsAsyncClient,
        properties: SnsProperties,
        batchExecutionStrategy: SnsBatchExecutionStrategy,
    ) : this(
        snsAsyncClient,
        properties,
        defaultSnsTopicArnResolver(snsAsyncClient, properties),
        batchExecutionStrategy,
        Unit,
    )

    /** resolver를 주입하고 기본 bounded batch strategy를 사용하는 생성자입니다. */
    constructor(
        snsAsyncClient: SnsAsyncClient,
        properties: SnsProperties,
        topicArnResolver: SnsTopicArnResolver,
    ) : this(snsAsyncClient, properties, topicArnResolver, DefaultSnsBatchExecutionStrategy, Unit)

    /** resolver와 명시적 SNS batch strategy를 함께 주입하는 생성자입니다. */
    constructor(
        snsAsyncClient: SnsAsyncClient,
        properties: SnsProperties,
        topicArnResolver: SnsTopicArnResolver,
        batchExecutionStrategy: SnsBatchExecutionStrategy,
    ) : this(snsAsyncClient, properties, topicArnResolver, batchExecutionStrategy, Unit)

    override suspend fun createTopic(
        topicName: String,
        attributes: Map<String, String>,
    ): String {
        topicName.requireTopicName()
        val topicArn = snsAsyncClient.createTopic {
            it.name(topicName)
            if (attributes.isNotEmpty()) {
                it.attributes(attributes)
            }
        }.await().topicArn()
        topicArnResolver.invalidate(topicName)
        return topicArn
    }

    override suspend fun createFifoTopic(
        topicName: String,
        contentBasedDeduplication: Boolean,
        fifoThroughputScope: SnsFifoThroughputScope?,
        attributes: Map<String, String>,
    ): String {
        topicName.requireTopicName()
        require(topicName.endsWith(".fifo")) {
            "FIFO topic name must end with .fifo."
        }

        val fifoAttributes = buildMap {
            putAll(attributes)
            put("FifoTopic", "true")
            put("ContentBasedDeduplication", contentBasedDeduplication.toString())
            fifoThroughputScope?.let { put("FifoThroughputScope", it.attributeValue) }
        }
        return createTopic(topicName, fifoAttributes)
    }

    override suspend fun createConfiguredTopic(topicName: String): String {
        topicName.requireTopicName()
        val topic = properties.topics[topicName]
            ?: throw IllegalArgumentException("Topic '$topicName' is not configured.")

        return if (topic.fifo) {
            createFifoTopic(
                topicName = topicName,
                contentBasedDeduplication = topic.contentBasedDeduplication,
                fifoThroughputScope = topic.fifoThroughputScope,
                attributes = topic.attributes,
            )
        } else {
            createTopic(topicName, topic.attributes)
        }
    }

    override suspend fun findTopicArn(topicName: String): String? {
        return if (topicName.trim().startsWith("arn:")) {
            topicArnResolver.resolve(topicName)
        } else {
            topicArnResolver.findTopicArn(topicName)
        }
    }

    override suspend fun publish(request: SnsPublishRequest): PublishResponse =
        snsAsyncClient.publish {
            it.topicArn(request.topicArn)
            it.message(request.message)
            request.subject?.let(it::subject)
            if (request.messageAttributes.isNotEmpty()) {
                it.messageAttributes(request.messageAttributes)
            }
            request.messageGroupId?.let(it::messageGroupId)
            request.messageDeduplicationId?.let(it::messageDeduplicationId)
        }.await()

    @Suppress("TooGenericExceptionCaught")
    override suspend fun publishBatch(
        request: SnsPublishBatchRequest,
        options: SnsBatchExecutionOptions,
    ): SnsPublishBatchResult {
        if (request.entries.isEmpty()) {
            return SnsPublishBatchResult(emptyList(), emptyList())
        }

        val guard = SnsBatchExecutionGuard(snsAsyncClient, request, options)
        var result: SnsPublishBatchResult? = null
        var failure: Throwable? = null
        try {
            result = batchExecutionStrategy.execute(request, options, guard)
            guard.validateAggregate(result, request)
        } catch (cause: Throwable) {
            failure = normalizeStrategyFailure(cause)
        } finally {
            try {
                withContext(NonCancellable) {
                    guard.closeAndDrain()
                }
            } catch (drainFailure: Throwable) {
                failure = failure?.also { it.addSuppressed(drainFailure) } ?: drainFailure
            }
        }

        failure?.let { throw it }
        return requireNotNull(result)
    }

    override suspend fun publishSms(request: SnsSmsRequest): PublishResponse =
        snsAsyncClient.publish {
            it.phoneNumber(request.phoneNumber)
            it.message(request.message)
            val attributes = request.toMessageAttributes()
            if (attributes.isNotEmpty()) {
                it.messageAttributes(attributes)
            }
        }.await()

    override suspend fun confirmSubscription(
        topicArn: String,
        token: String,
        authenticateOnUnsubscribe: Boolean,
    ): ConfirmSubscriptionResponse {
        topicArn.requireTopicArn()
        require(token.isNotBlank()) { "token must not be blank." }

        return snsAsyncClient.confirmSubscription {
            it.topicArn(topicArn)
            it.token(token)
            it.authenticateOnUnsubscribe(authenticateOnUnsubscribe.toString())
        }.await()
    }

    override suspend fun confirmSubscription(
        message: SnsHttpMessage,
        authenticateOnUnsubscribe: Boolean,
    ): ConfirmSubscriptionResponse =
        confirmSubscription(
            topicArn = message.topicArn,
            token = message.requireConfirmationToken(),
            authenticateOnUnsubscribe = authenticateOnUnsubscribe,
        )

    private fun String.requireTopicArn() {
        require(isNotBlank()) { "topicArn must not be blank." }
    }

    private fun String.requireTopicName() {
        require(isNotBlank()) { "topicName must not be blank." }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun normalizeStrategyFailure(cause: Throwable): Throwable = when (cause) {
        is CancellationException,
        is SnsBatchTransportException,
        is SnsBatchProtocolException,
        is SnsBatchExecutionContractException,
        is Error -> cause
        else -> SnsBatchExecutionContractException(SnsBatchExecutionContractError.STRATEGY_FAILURE)
    }
}

private fun defaultSnsTopicArnResolver(
    snsAsyncClient: SnsAsyncClient,
    properties: SnsProperties,
): SnsTopicArnResolver =
    SnsTopicArnResolver(
        snsAsyncClient = snsAsyncClient,
        cache = if (properties.topicArnCache.enabled) {
            InMemorySnsTopicArnCache(
                maxSize = properties.topicArnCache.maxSize,
                ttl = properties.topicArnCache.ttl,
            )
        } else {
            NoopSnsTopicArnCache
        },
        scope = SnsTopicArnResolverScope(
            endpointOverride = properties.endpointOverride,
            region = properties.region,
            accountId = properties.accountId,
        ),
        allowCrossAccountTopicArn = properties.allowCrossAccountTopicArn,
    )
