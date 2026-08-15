package io.bluetape4k.aws.kotlin.sns

import aws.sdk.kotlin.services.sns.model.PublishBatchRequestEntry
import io.bluetape4k.support.requireNotBlank

@PublishedApi
internal fun validatePublishBatchRequest(
    topicArn: String,
    entries: List<PublishBatchRequestEntry>,
) {
    topicArn.requireNotBlank("topicArn")
    require(entries.isNotEmpty()) { "entries must not be empty." }
    require(entries.size <= 10) { "entries must contain at most 10 items." }

    val ids = entries.map { entry ->
        entry.id.requireNotBlank("entry.id")
        entry.message.requireNotBlank("entry.message")
        entry.id
    }
    require(ids.size == ids.toSet().size) { "entries must have distinct ids." }
}
