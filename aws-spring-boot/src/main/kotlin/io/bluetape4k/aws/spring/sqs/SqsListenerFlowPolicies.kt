package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireNotBlank
import kotlinx.coroutines.sync.Mutex
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** 리스너 handler admission 상한을 계산하는 방식입니다. */
enum class SqsBackPressureMode {
    /** 설정한 [SqsProperties.Listener.maxInFlight]를 정확히 사용합니다. */
    FIXED,

    /** poller와 batch 크기를 기준으로 필요한 최소 handler slot을 계산합니다. */
    AUTO,
}

/** FIFO 메시지 group의 처리 순서를 적용하는 방식입니다. */
enum class SqsFifoBatchGroupingStrategy {
    /** `messageGroupId`가 같은 단건 handler를 하나씩 실행합니다. */
    GROUP_BY_MESSAGE_GROUP_ID,

    /** group 순서 보장이 필요하지 않은 일반 queue 경로입니다. */
    DISABLED,
}

/** queue URL을 찾지 못했을 때 listener가 취할 동작입니다. */
enum class SqsQueueNotFoundStrategy {
    /** 첫 조회 오류를 listener generation 실패로 전파합니다. */
    FAIL_FAST,

    /** 설정된 queue 이름으로 `createConfiguredQueue`를 한 번 호출합니다. */
    CREATE,

    /** 해당 generation을 조용히 중지하고 재시도하지 않습니다. */
    IGNORE,
}

/** queue attribute 조회 결과를 URL과 함께 보관합니다. */
data class SqsQueueAttributes(
    val queueUrl: String,
    val values: Map<QueueAttributeName, String>,
) {
    init {
        queueUrl.requireNotBlank("queueUrl")
    }

    val isFifo: Boolean
        get() = values[QueueAttributeName.FIFO_QUEUE].equals("true", ignoreCase = true) ||
            queueUrl.endsWith(".fifo", ignoreCase = true)
}

/** queue URL과 attribute 이름을 받아 cache 가능한 속성 결과를 반환합니다. */
fun interface SqsQueueAttributesResolver {
    suspend fun resolve(
        queueUrl: String,
        attributeNames: Set<QueueAttributeName>,
    ): SqsQueueAttributes
}

/** 성공한 queue attribute만 TTL 동안 보관하는 기본 resolver입니다. */
class DefaultSqsQueueAttributesResolver(
    private val operations: SqsOperations,
    private val cacheTtl: Duration = Duration.ofMinutes(1),
    private val nanoTime: () -> Long = System::nanoTime,
) : SqsQueueAttributesResolver {

    private data class CachedAttributes(
        val attributes: SqsQueueAttributes,
        val expiresAtNanos: Long,
    )

    private val cache = ConcurrentHashMap<String, CachedAttributes>()

    init {
        cacheTtl.requireGe(Duration.ZERO, "cacheTtl")
    }

    override suspend fun resolve(
        queueUrl: String,
        attributeNames: Set<QueueAttributeName>,
    ): SqsQueueAttributes {
        queueUrl.requireNotBlank("queueUrl")
        val names = attributeNames.toSet()
        val key = cacheKey(queueUrl, names)
        val now = nanoTime()
        cache[key]?.takeIf { it.expiresAtNanos > now }?.let { return it.attributes }

        val values = if (names.isEmpty()) {
            emptyMap()
        } else {
            operations.getQueueAttributes(queueUrl, names).toMap()
        }
        val attributes = SqsQueueAttributes(queueUrl, values)
        if (!cacheTtl.isZero) {
            cache[key] = CachedAttributes(
                attributes = attributes,
                expiresAtNanos = now + cacheTtl.toNanos().coerceAtLeast(1L),
            )
        }
        return attributes
    }

    internal fun clear() {
        cache.clear()
    }

    private fun cacheKey(queueUrl: String, attributeNames: Set<QueueAttributeName>): String =
        buildString {
            append(queueUrl)
            append('|')
            attributeNames.map(QueueAttributeName::toString).sorted().joinTo(this, separator = ",")
        }
}

internal typealias SqsGroupMutexes = ConcurrentHashMap<String, Mutex>
