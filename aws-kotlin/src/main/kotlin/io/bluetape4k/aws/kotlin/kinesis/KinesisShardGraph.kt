package io.bluetape4k.aws.kotlin.kinesis

import aws.sdk.kotlin.services.kinesis.KinesisClient
import aws.sdk.kotlin.services.kinesis.listShards
import aws.sdk.kotlin.services.kinesis.model.KinesisException
import aws.sdk.kotlin.services.kinesis.model.ListShardsRequest
import aws.sdk.kotlin.services.kinesis.model.Shard
import io.bluetape4k.aws.kotlin.PaginationFailure
import io.bluetape4k.aws.kotlin.PaginationGuard
import io.bluetape4k.logging.KotlinLogging
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

private val graphLog = KotlinLogging.logger { }

/** ListShards 결과를 consumer가 사용할 수 있는 immutable dependency graph로 표현합니다. */
internal data class KinesisShardGraph(
    val nodes: Map<String, KinesisShardNode>,
) {
    val roots: List<KinesisShardNode>
        get() = nodes.values.filter { it.dependencies.isEmpty() }
}

/** 한 shard와 두 parent dependency를 표현하는 내부 graph node입니다. */
internal data class KinesisShardNode(
    val shardId: String,
    val dependencies: Set<String>,
    val endingSequenceNumber: String?,
)

/**
 * ListShards pagination을 bounded하게 수집해 완전한 graph를 반환합니다.
 *
 * token이 만료되거나 retryable 오류가 발생하면 지금까지 읽은 partial list를 폐기하고
 * 처음부터 다시 수집합니다. parent가 누락된 목록은 root로 승격하지 않고 bounded retry합니다.
 */
internal suspend fun KinesisClient.discoverKinesisShardGraph(
    streamName: String,
    options: KinesisConsumerOptions,
): KinesisShardGraph {
    var unknownParentAttempt = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val shards = listAllShards(streamName, options)
        val graph = buildGraph(shards, options)
        val unknownParents = graph.nodes.values
            .flatMap { it.dependencies }
            .filterNot { it in graph.nodes }
            .toSet()
        if (unknownParents.isEmpty()) return graph

        unknownParentAttempt++
        if (unknownParentAttempt >= options.maxUnknownParentDiscoveries) {
            throw KinesisShardGraphException(
                "unknown shard parent dependencies exceeded maxUnknownParentDiscoveries=" +
                        options.maxUnknownParentDiscoveries,
            )
        }
        graphLog.warn {
            "Kinesis shard graph has unknown parent dependencies; retrying discovery " +
                    "attempt=$unknownParentAttempt/${options.maxUnknownParentDiscoveries}"
        }
        delay(options.discoveryInterval)
    }
}

@Suppress("ThrowsCount")
private suspend fun KinesisClient.listAllShards(
    streamName: String,
    options: KinesisConsumerOptions,
): List<Shard> {
    var discoveryRetry = 0
    while (true) {
        try {
            return collectShardPages(streamName, options)
        } catch (e: CancellationException) {
            throw e
        } catch (e: KinesisException) {
            if (!e.sdkErrorMetadata.isRetryable && !e.isExpiredNextTokenFailure()) throw e
            discoveryRetry++
            if (discoveryRetry > options.maxDiscoveryRetries) throw e
            graphLog.warn {
                "Kinesis shard discovery retry $discoveryRetry/${options.maxDiscoveryRetries}: " +
                        "type=${e::class.simpleName}"
            }
            delay(options.recordOptions.initialThrottleBackoff)
        }
    }
}

@Suppress("ThrowsCount")
private suspend fun KinesisClient.collectShardPages(
    streamName: String,
    options: KinesisConsumerOptions,
): List<Shard> {
    val shardsById = linkedMapOf<String, Shard>()
    var token: String? = null
    val paginationGuard = PaginationGuard<String>(options.maxListShardsPages) { failure ->
        KinesisShardGraphException(
            when (failure) {
                PaginationFailure.MISSING_TOKEN -> "ListShards returned an invalid pagination token"
                PaginationFailure.REPEATED_TOKEN -> "ListShards returned a non-progressing nextToken"
                PaginationFailure.PAGE_LIMIT_EXCEEDED ->
                    "ListShards pagination exceeded maxListShardsPages=${options.maxListShardsPages}"
            },
        )
    }

    while (true) {
        currentCoroutineContext().ensureActive()
        val response = listShards(ListShardsRequest {
            this.streamName = streamName
            this.nextToken = token
            maxResults = minOf(options.maxRecordsPerPoll, 1_000)
        })
        response.shards.orEmpty().filterNotNull().forEach { shard ->
            val shardId = shard.shardId.also {
                it.validateIdentifier("shardId", KinesisShardKey.MAX_IDENTIFIER_LENGTH)
            }
            shardsById.putIfAbsent(shardId, shard)
            if (shardsById.size > options.maxDiscoveredShards) {
                throw KinesisShardGraphException(
                    "discovered shard count exceeded maxDiscoveredShards=${options.maxDiscoveredShards}",
                )
            }
        }

        val nextToken = response.nextToken?.takeUnless { it.isBlank() }
        token = paginationGuard.nextTokenOrNull(
            hasNext = nextToken != null,
            nextToken = nextToken,
        ) ?: return shardsById.values.toList()
    }
}

private fun buildGraph(shards: List<Shard>, options: KinesisConsumerOptions): KinesisShardGraph {
    val nodes = linkedMapOf<String, KinesisShardNode>()
    shards.forEach { shard ->
        val shardId = shard.shardId.also {
            it.validateIdentifier("shardId", KinesisShardKey.MAX_IDENTIFIER_LENGTH)
        }
        val dependencies = buildSet {
            shard.parentShardId?.let(::add)
            shard.adjacentParentShardId?.let(::add)
        }.also { parents ->
            parents.forEach { it.validateIdentifier("parentShardId", KinesisShardKey.MAX_IDENTIFIER_LENGTH) }
        }
        nodes[shardId] = KinesisShardNode(
            shardId = shardId,
            dependencies = dependencies,
            endingSequenceNumber = shard.sequenceNumberRange?.endingSequenceNumber,
        )
    }
    require(nodes.size <= options.maxDiscoveredShards) {
        "discovered shard count exceeded maxDiscoveredShards=${options.maxDiscoveredShards}"
    }
    ensureAcyclic(nodes)
    return KinesisShardGraph(nodes)
}

private fun ensureAcyclic(nodes: Map<String, KinesisShardNode>) {
    val visiting = mutableSetOf<String>()
    val visited = mutableSetOf<String>()

    fun visit(id: String) {
        if (!visiting.add(id)) {
            throw KinesisShardGraphException("shard graph contains a dependency cycle")
        }
        if (visited.add(id)) {
            nodes[id]?.dependencies.orEmpty().forEach(::visit)
        }
        visiting.remove(id)
    }

    nodes.keys.forEach(::visit)
}

private fun KinesisException.isExpiredNextTokenFailure(): Boolean {
    val type = this::class.simpleName.orEmpty()
    return type.contains("ExpiredNextToken", ignoreCase = true) ||
            type.contains("InvalidArgument", ignoreCase = true) &&
            message.orEmpty().contains("nextToken", ignoreCase = true)
}
