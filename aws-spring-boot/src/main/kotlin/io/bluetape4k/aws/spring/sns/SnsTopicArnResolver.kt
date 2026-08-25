package io.bluetape4k.aws.spring.sns

import io.bluetape4k.logging.KLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import software.amazon.awssdk.services.sns.SnsAsyncClient
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val AWS_ACCOUNT_ID_PATTERN: Regex = Regex("\\d{12}")

private fun SnsTopicArnResolverScope.stableHash(): Int =
    listOf(endpointOverride?.toString(), region, accountId).hashCode()

/**
 * SNS topic ARN cache를 분리하는 실행 scope입니다.
 *
 * endpoint, region, account가 달라지면 같은 topic name도 다른 AWS 리소스를
 * 가리킬 수 있으므로 세 값을 cache key에 포함합니다.
 */
data class SnsTopicArnResolverScope(
    val endpointOverride: URI? = null,
    val region: String? = null,
    val accountId: String? = null,
    val cacheNamespace: String = UUID.randomUUID().toString(),
): java.io.Serializable {

    init {
        endpointOverride?.let {
            require(it.userInfo == null) { "endpointOverride must not contain user info." }
            require(it.query == null) { "endpointOverride must not contain a query." }
            require(it.fragment == null) { "endpointOverride must not contain a fragment." }
        }
        region?.let { require(it.isNotBlank()) { "region must not be blank." } }
        accountId?.let {
            require(it.matches(AWS_ACCOUNT_ID_PATTERN)) {
                "accountId must be a 12-digit AWS account ID."
            }
        }
        require(cacheNamespace.isNotBlank()) { "cacheNamespace must not be blank." }
    }

    override fun toString(): String =
        "SnsTopicArnResolverScope(" +
            "endpointOverride=${endpointOverride?.let { "${it.scheme}://${it.host}" }}, " +
            "region=$region, accountId=${accountId?.let { "<configured>" }}, " +
            "cacheNamespace=<isolated>" +
            ")"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * SNS topic name과 resolver scope를 함께 식별하는 cache key입니다.
 */
data class SnsTopicArnCacheKey(
    val scope: SnsTopicArnResolverScope,
    val topicName: String,
): java.io.Serializable {

    init {
        require(topicName.isNotBlank()) { "topicName must not be blank." }
    }

    override fun toString(): String =
        "SnsTopicArnCacheKey(scope=$scope, topicNameHash=${topicName.hashCode()})"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * resolver가 cache에 저장하는 positive/negative 조회 결과입니다.
 */
sealed interface SnsTopicArnCacheEntry: java.io.Serializable {

    /** 확인된 topic ARN입니다. */
    data class Resolved(
        val topicArn: String,
    ): SnsTopicArnCacheEntry {
        init {
            require(topicArn.isNotBlank()) { "topicArn must not be blank." }
        }

        override fun toString(): String = "Resolved(topicArn=<redacted>)"

        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    /** AWS 목록에 topic이 없음을 나타내는 bounded negative entry입니다. */
    data object NotFound : SnsTopicArnCacheEntry {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * SNS topic ARN cache 계약입니다.
 */
interface SnsTopicArnCache {

    fun get(key: SnsTopicArnCacheKey): SnsTopicArnCacheEntry?

    fun put(key: SnsTopicArnCacheKey, entry: SnsTopicArnCacheEntry)

    fun invalidate(key: SnsTopicArnCacheKey)

    fun clear()
}

/**
 * 값을 저장하지 않는 SNS topic ARN cache입니다.
 *
 * cache를 끄더라도 resolver의 single-flight 중복 억제는 유지됩니다.
 */
object NoopSnsTopicArnCache : SnsTopicArnCache {

    override fun get(key: SnsTopicArnCacheKey): SnsTopicArnCacheEntry? = null

    override fun put(key: SnsTopicArnCacheKey, entry: SnsTopicArnCacheEntry) = Unit

    override fun invalidate(key: SnsTopicArnCacheKey) = Unit

    override fun clear() = Unit
}

/**
 * TTL과 access-order LRU를 적용하는 bounded in-memory SNS topic ARN cache입니다.
 */
class InMemorySnsTopicArnCache(
    private val maxSize: Int = DEFAULT_MAX_SIZE,
    private val ttl: Duration = DEFAULT_TTL,
    private val clock: Clock = Clock.systemUTC(),
): SnsTopicArnCache {

    private data class Entry(
        val value: SnsTopicArnCacheEntry,
        val expiresAt: Instant,
    )

    private val lock = ReentrantLock()

    private val entries = object: LinkedHashMap<SnsTopicArnCacheKey, Entry>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<SnsTopicArnCacheKey, Entry>?,
        ): Boolean = size > maxSize
    }

    init {
        require(maxSize > 0) { "maxSize must be greater than 0." }
        require(!ttl.isNegative && !ttl.isZero) { "ttl must be greater than zero." }
        require(ttl <= MAX_TTL) { "ttl must not exceed 24 hours." }
    }

    override fun get(key: SnsTopicArnCacheKey): SnsTopicArnCacheEntry? =
        lock.withLock {
            val entry = entries[key] ?: return null
            if (entry.expiresAt.isAfter(clock.instant())) {
                entry.value
            } else {
                entries.remove(key)
                null
            }
        }

    override fun put(key: SnsTopicArnCacheKey, entry: SnsTopicArnCacheEntry) {
        lock.withLock {
            entries[key] = Entry(entry, clock.instant().plus(ttl))
        }
    }

    override fun invalidate(key: SnsTopicArnCacheKey) {
        lock.withLock {
            entries.remove(key)
        }
    }

    override fun clear() {
        lock.withLock {
            entries.clear()
        }
    }

    companion object {
        val DEFAULT_TTL: Duration = Duration.ofMinutes(5)
        val MAX_TTL: Duration = Duration.ofHours(24)
        const val DEFAULT_MAX_SIZE: Int = 256
    }
}

/**
 * topic name 또는 명시적 ARN을 SNS topic ARN으로 정규화하고 조회합니다.
 *
 * name 조회는 `ListTopics` pagination을 사용하며, 같은 scope와 name에 대한
 * 동시 호출은 key별 mutex로 하나의 AWS 조회만 실행합니다. AWS 오류와
 * cancellation은 cache에 저장하지 않고 호출자에게 전파합니다.
 */
@Suppress("TooManyFunctions")
class SnsTopicArnResolver(
    private val snsAsyncClient: SnsAsyncClient,
    private val cache: SnsTopicArnCache = InMemorySnsTopicArnCache(),
    val scope: SnsTopicArnResolverScope = SnsTopicArnResolverScope(),
    private val allowCrossAccountTopicArn: Boolean = false,
) {

    private sealed interface FlightOutcome {
        data class Success(val topicArn: String?): FlightOutcome
        data class Failure(val cause: Throwable): FlightOutcome
    }

    private class Flight {
        val mutex: Mutex = Mutex()
        @Volatile
        var invalidated: Boolean = false
        var users: Int = 0
        var outcome: FlightOutcome? = null
    }

    private val flightsLock = ReentrantLock()
    private val flights = mutableMapOf<SnsTopicArnCacheKey, Flight>()

    /**
     * topic name 또는 ARN을 정규화합니다. 명시적 ARN은 AWS 조회를 우회합니다.
     */
    suspend fun resolve(topicReference: String): String? {
        val normalized = topicReference.trim()
        require(normalized.isNotBlank()) { "topicReference must not be blank." }
        if (normalized.startsWith("arn:")) {
            return validateExplicitArn(normalized)
        }
        return resolveName(normalized.requireTopicName())
    }

    /**
     * 기존 name 중심 API와 호환되는 resolver 진입점입니다.
     */
    suspend fun findTopicArn(topicName: String): String? = resolve(topicName)

    /**
     * 해당 name의 positive/negative cache entry를 제거합니다.
     */
    fun invalidate(topicName: String) {
        val normalized = topicName.trim()
        require(normalized.isNotBlank()) { "topicName must not be blank." }
        if (!normalized.startsWith("arn:")) {
            val key = SnsTopicArnCacheKey(scope, normalized.requireTopicName())
            flightsLock.withLock {
                flights.remove(key)?.invalidated = true
                cache.invalidate(key)
            }
        }
    }

    /** 모든 scope entry를 제거합니다. */
    fun clear() {
        flightsLock.withLock {
            flights.values.forEach { it.invalidated = true }
            flights.clear()
            cache.clear()
        }
    }

    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    private suspend fun resolveName(topicName: String): String? {
        val key = SnsTopicArnCacheKey(scope, topicName)
        cache.get(key)?.let { cached ->
            return when (cached) {
                is SnsTopicArnCacheEntry.Resolved -> {
                    try {
                        validateLookupArn(cached.topicArn, topicName)
                    } catch (cause: IllegalArgumentException) {
                        cache.invalidate(key)
                        throw cause
                    }
                }
                SnsTopicArnCacheEntry.NotFound -> null
            }
        }

        val flight = acquireFlight(key)
        try {
            return flight.mutex.withLock {
                when (val outcome = flight.outcome) {
                    is FlightOutcome.Success -> outcome.topicArn
                    is FlightOutcome.Failure -> throw outcome.cause
                    null -> {
                        try {
                            val topicArn = lookupTopicArn(topicName)
                            putUnlessInvalidated(
                                key = key,
                                flight = flight,
                                entry = topicArn?.let(SnsTopicArnCacheEntry::Resolved)
                                    ?: SnsTopicArnCacheEntry.NotFound,
                            )
                            flight.outcome = FlightOutcome.Success(topicArn)
                            topicArn
                        } catch (cause: Throwable) {
                            if (cause is CancellationException) {
                                throw cause
                            }
                            log.warn(
                                "SNS topic ARN lookup failed (scopeHash={}, topicNameHash={}, exceptionType={})",
                                scope.stableHash(),
                                topicName.hashCode(),
                                cause::class.java.simpleName,
                            )
                            flight.outcome = FlightOutcome.Failure(cause)
                            throw cause
                        }
                    }
                }
            }
        } finally {
            releaseFlight(key, flight)
        }
    }

    private suspend fun lookupTopicArn(topicName: String): String? {
        val suffix = ":$topicName"
        var nextToken: String? = null
        do {
            val response = snsAsyncClient.listTopics {
                nextToken?.let(it::nextToken)
            }.await()
            response.topics().orEmpty()
                .asSequence()
                .mapNotNull { it.topicArn() }
                .firstOrNull { it.endsWith(suffix) }
                ?.let { return validateLookupArn(it, topicName) }
            nextToken = response.nextToken()
        } while (!nextToken.isNullOrBlank())
        return null
    }

    private fun acquireFlight(key: SnsTopicArnCacheKey): Flight =
        flightsLock.withLock {
            flights.getOrPut(key) { Flight() }.also { it.users += 1 }
        }

    private fun releaseFlight(key: SnsTopicArnCacheKey, flight: Flight) {
        flightsLock.withLock {
            flight.users -= 1
            if (flight.users == 0) {
                flights.remove(key, flight)
            }
        }
    }

    private fun putUnlessInvalidated(
        key: SnsTopicArnCacheKey,
        flight: Flight,
        entry: SnsTopicArnCacheEntry,
    ) {
        flightsLock.withLock {
            if (!flight.invalidated) {
                cache.put(key, entry)
            }
        }
    }

    private fun validateExplicitArn(arn: String): String {
        val parsed = parseSnsArn(arn)
        require(scope.region != null) {
            "resolver region must be configured before resolving an explicit ARN."
        }
        validateRegion(parsed.region)
        validateAccount(parsed.accountId, explicit = true)
        return arn
    }

    private fun validateLookupArn(arn: String, topicName: String): String {
        val parsed = parseSnsArn(arn)
        require(parsed.topicName == topicName) {
            "ListTopics returned an ARN for a different topic name."
        }
        validateRegion(parsed.region)
        validateAccount(parsed.accountId, explicit = false)
        return arn
    }

    private fun parseSnsArn(
        arn: String,
        accountPattern: Regex = AWS_ACCOUNT_ID_PATTERN,
    ): ParsedSnsArn {
        val parts = arn.split(':', limit = ARN_PART_COUNT)
        require(
            parts.size == ARN_PART_COUNT &&
                parts[0] == "arn" &&
                parts[1].matches(PARTITION_PATTERN) &&
                parts[2] == "sns",
        ) {
            "topicArn must be a valid SNS ARN."
        }
        val region = parts[3]
        val accountId = parts[4]
        val topicName = parts[5]
        require(
            region.matches(REGION_PATTERN) &&
                accountId.matches(accountPattern) &&
                topicName.isValidSnsTopicName(),
        ) {
            "topicArn must be a valid SNS topic ARN."
        }
        return ParsedSnsArn(region, accountId, topicName)
    }

    private fun validateRegion(region: String) {
        scope.region?.let {
            require(region == it) {
                "topicArn region '$region' does not match resolver region '$it'."
            }
        }
    }

    private fun validateAccount(accountId: String, explicit: Boolean) {
        val configuredAccountId = scope.accountId
        if (configuredAccountId == null) {
            if (explicit) {
                require(allowCrossAccountTopicArn) {
                    "scope.accountId must be configured before resolving an explicit ARN " +
                        "unless cross-account topicArn is enabled."
                }
            }
            return
        }
        if (!explicit) {
            require(accountId == configuredAccountId) {
                "ListTopics returned topicArn account '$accountId' that does not match " +
                    "resolver account '$configuredAccountId'."
            }
            return
        }
        if (accountId != configuredAccountId) {
            require(allowCrossAccountTopicArn) {
                "Cross-account topicArn is disabled for this resolver."
            }
        }
    }

    private data class ParsedSnsArn(
        val region: String,
        val accountId: String,
        val topicName: String,
    )

    private fun String.requireTopicName(): String {
        require(isValidSnsTopicName()) {
            "topicName must contain only letters, numbers, hyphens, underscores, and an optional .fifo suffix."
        }
        return this
    }

    private fun String.isValidSnsTopicName(): Boolean =
        length in MIN_TOPIC_NAME_LENGTH..MAX_TOPIC_NAME_LENGTH && matches(TOPIC_NAME_PATTERN)

    companion object : KLogging() {
        private const val ARN_PART_COUNT: Int = 6
        private val PARTITION_PATTERN: Regex = Regex("[A-Za-z0-9-]+")
        private val REGION_PATTERN: Regex = Regex("[a-z0-9-]+")
        private const val MIN_TOPIC_NAME_LENGTH: Int = 1
        private const val MAX_TOPIC_NAME_LENGTH: Int = 256
        private val TOPIC_NAME_PATTERN: Regex = Regex("[A-Za-z0-9_-]+(?:\\.fifo)?")
    }
}
