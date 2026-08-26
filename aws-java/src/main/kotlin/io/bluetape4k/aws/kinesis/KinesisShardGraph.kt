package io.bluetape4k.aws.kinesis

import software.amazon.awssdk.services.kinesis.model.Shard

/**
 * 완전한 `ListShards` 결과를 parent dependency graph로 정규화한 값입니다.
 *
 * parent ID가 응답에 있는데 목록에 없는 shard는 root로 승격하지 않습니다. 호출자는 다음
 * discovery에서 완전한 목록을 다시 수집하거나 bounded failure를 처리해야 합니다.
 */
class KinesisShardGraph private constructor(
    val nodes: List<Node>,
) {

    /** 하나의 shard와 완료되어야 하는 parent dependency입니다. */
    data class Node(
        val shard: Shard,
        val dependencies: Set<String>,
    )

    companion object {
        /** 중복 ID를 합치고 parent/adjacent parent를 모두 dependency로 검증합니다. */
        fun from(shards: List<Shard>, maxDiscoveredShards: Int): KinesisShardGraph {
            require(maxDiscoveredShards >= 1) { "maxDiscoveredShards must be >= 1" }
            val byId = linkedMapOf<String, Shard>()
            for (shard in shards) {
                val id = shard.shardId()?.requireKinesisIdentifier("shardId")
                    ?: throw KinesisShardGraphException("ListShards returned a shard without shardId")
                byId.putIfAbsent(id, shard)
                if (byId.size > maxDiscoveredShards) {
                    throw KinesisShardGraphException("discovered shard count exceeded configured maximum")
                }
            }

            val nodes = byId.values.map { shard ->
                val id = requireNotNull(shard.shardId())
                val parentIds = listOfNotNull(shard.parentShardId(), shard.adjacentParentShardId())
                    .map { it.requireKinesisIdentifier("parentShardId") }
                    .toSet()
                Node(shard = shard, dependencies = parentIds)
            }
            ensureAcyclic(nodes)
            return KinesisShardGraph(nodes)
        }

        private fun ensureAcyclic(nodes: List<Node>) {
            val dependencies = nodes.associateBy { requireNotNull(it.shard.shardId()) }
            val visiting = mutableSetOf<String>()
            val visited = mutableSetOf<String>()

            fun visit(id: String) {
                if (id in visiting) {
                    throw KinesisShardGraphException("shard graph contains a dependency cycle")
                }
                if (!visited.add(id)) return
                visiting.add(id)
                dependencies[id]?.dependencies.orEmpty().forEach(::visit)
                visiting.remove(id)
            }
            nodes.forEach { visit(requireNotNull(it.shard.shardId())) }
        }
    }
}
