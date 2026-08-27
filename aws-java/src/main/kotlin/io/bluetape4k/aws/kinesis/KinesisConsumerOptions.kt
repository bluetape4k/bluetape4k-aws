package io.bluetape4k.aws.kinesis

import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * multi-shard consumer의 discovery, lease, backpressure, cleanup 상한입니다.
 *
 * [ownerId]는 deployment 또는 instance 단위로 호출자가 유일하게 생성해야 합니다. 라이브러리는
 * worker 간 전역 유일성을 확인할 수 없으며 자동 UUID를 생성하지 않습니다.
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
    private fun readObject(input: java.io.ObjectInputStream) {
        input.defaultReadObject()
        validate()
    }

    private fun validate() {
        ownerId.requireKinesisIdentifier("ownerId")
        require(maxShardConcurrency >= 1) { "maxShardConcurrency must be >= 1" }
        require(discoveryInterval.isPositive()) { "discoveryInterval must be positive" }
        require(leaseDuration > leaseRenewInterval) {
            "leaseDuration ($leaseDuration) must be greater than leaseRenewInterval ($leaseRenewInterval)"
        }
        require(leaseRenewInterval.isPositive()) { "leaseRenewInterval must be positive" }
        require(maxListShardsPages >= 1) { "maxListShardsPages must be >= 1" }
        require(maxDiscoveryRetries >= 0) { "maxDiscoveryRetries must be >= 0" }
        require(maxUnknownParentDiscoveries >= 1) { "maxUnknownParentDiscoveries must be >= 1" }
        require(maxDiscoveredShards >= 1) { "maxDiscoveredShards must be >= 1" }
        require(maxRecordsPerPoll >= 1) { "maxRecordsPerPoll must be >= 1" }
        require(leaseReleaseTimeout.isPositive()) { "leaseReleaseTimeout must be positive" }
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
