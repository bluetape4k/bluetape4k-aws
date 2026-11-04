package io.bluetape4k.aws.kinesis.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.kinesis.model.ListShardsRequest

inline fun listShardsRequest(
    builder: ListShardsRequest.Builder.() -> Unit,
): ListShardsRequest =
    ListShardsRequest.builder().apply(builder).build()

inline fun listShardsRequestOf(
    streamARN: String? = null,
    streamId: String? = null,
    streamName: String? = null,
    maxResults: Int? = null,
    builder: ListShardsRequest.Builder.() -> Unit = {},
): ListShardsRequest =
    listShardsRequest {
        streamARN?.let { this.streamARN(it.requireNotBlank("streamARN")) }
        streamId?.let { this.streamId(it.requireNotBlank("streamId")) }
        streamName?.let { this.streamName(it.requireNotBlank("streamName")) }
        maxResults?.let { this.maxResults(it) }
        builder()
    }
