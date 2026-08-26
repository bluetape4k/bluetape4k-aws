package io.bluetape4k.aws.kotlin.kinesis

import java.io.ObjectInputStream
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Kinesis multi-shard consumer의 bounded polling·lease·discovery 옵션입니다.
 *
 * `ownerId`는 worker 배포 단위에서 전역적으로 유일한 값을 호출자가 제공해야 합니다.
 * library는 형식만 검증하고 여러 프로세스의 전역 유일성을 확인하지 않습니다.
 */
data class KinesisConsumerOptions(
    val ownerId: String,
    val recordOptions: KinesisRecordFlowOptions = KinesisRecordFlowOptions(),
    val maxShardConcurrency: Int = DEFAULT_MAX_SHARD_CONCURRENCY,
    val discoveryInterval: Duration = DEFAULT_DISCOVERY_INTERVAL,
    val leaseDuration: Duration = DEFAULT_LEASE_DURATION,
    val leaseRenewInterval: Duration = DEFAULT_LEASE_RENEW_INTERVAL,
    val maxListShardsPages: Int = DEFAULT_MAX_LIST_SHARDS_PAGES,
    val maxDiscoveryRetries: Int = DEFAULT_MAX_DISCOVERY_RETRIES,
    val maxUnknownParentDiscoveries: Int = DEFAULT_MAX_UNKNOWN_PARENT_DISCOVERIES,
    val maxDiscoveredShards: Int = DEFAULT_MAX_DISCOVERED_SHARDS,
    val maxRecordsPerPoll: Int = DEFAULT_MAX_RECORDS_PER_POLL,
    val leaseReleaseTimeout: Duration = DEFAULT_LEASE_RELEASE_TIMEOUT,
) : Serializable {

    init {
        validate()
    }

    @Suppress("UnusedPrivateMember")
    private fun readObject(stream: ObjectInputStream) {
        stream.defaultReadObject()
        validate()
    }

    private fun validate() {
        ownerId.validateIdentifier("ownerId", KinesisShardKey.MAX_IDENTIFIER_LENGTH)
        require(maxShardConcurrency >= 1) {
            "maxShardConcurrency must be >= 1, but was $maxShardConcurrency"
        }
        require(discoveryInterval.isPositive()) {
            "discoveryInterval must be positive, but was $discoveryInterval"
        }
        require(leaseDuration.isPositive()) {
            "leaseDuration must be positive, but was $leaseDuration"
        }
        require(leaseRenewInterval.isPositive()) {
            "leaseRenewInterval must be positive, but was $leaseRenewInterval"
        }
        require(leaseDuration > leaseRenewInterval) {
            "leaseDuration ($leaseDuration) must be greater than leaseRenewInterval ($leaseRenewInterval)"
        }
        require(maxListShardsPages >= 1) {
            "maxListShardsPages must be >= 1, but was $maxListShardsPages"
        }
        require(maxDiscoveryRetries >= 0) {
            "maxDiscoveryRetries must be >= 0, but was $maxDiscoveryRetries"
        }
        require(maxUnknownParentDiscoveries >= 1) {
            "maxUnknownParentDiscoveries must be >= 1, but was $maxUnknownParentDiscoveries"
        }
        require(maxDiscoveredShards >= 1) {
            "maxDiscoveredShards must be >= 1, but was $maxDiscoveredShards"
        }
        require(maxRecordsPerPoll in 1..KinesisRecordFlowOptions.MAX_KINESIS_BATCH_LIMIT) {
            "maxRecordsPerPoll must be in 1..${KinesisRecordFlowOptions.MAX_KINESIS_BATCH_LIMIT}, " +
                    "but was $maxRecordsPerPoll"
        }
        require(leaseReleaseTimeout.isPositive()) {
            "leaseReleaseTimeout must be positive, but was $leaseReleaseTimeout"
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L

        const val DEFAULT_MAX_SHARD_CONCURRENCY: Int = 4
        val DEFAULT_DISCOVERY_INTERVAL: Duration = 5.seconds
        val DEFAULT_LEASE_DURATION: Duration = 60.seconds
        val DEFAULT_LEASE_RENEW_INTERVAL: Duration = 20.seconds
        const val DEFAULT_MAX_LIST_SHARDS_PAGES: Int = 100
        const val DEFAULT_MAX_DISCOVERY_RETRIES: Int = 3
        const val DEFAULT_MAX_UNKNOWN_PARENT_DISCOVERIES: Int = 3
        const val DEFAULT_MAX_DISCOVERED_SHARDS: Int = 10_000
        const val DEFAULT_MAX_RECORDS_PER_POLL: Int = 100
        val DEFAULT_LEASE_RELEASE_TIMEOUT: Duration = 5.seconds
    }
}

/** consumer에서 사용할 empty response 지연입니다. AWS shard quota 하한을 보장합니다. */
internal val KinesisConsumerOptions.effectiveEmptyBackoff: Duration
    get() = maxOf(recordOptions.emptyBackoff, KinesisRecordFlowOptions.MIN_POLL_INTERVAL)
