package io.bluetape4k.aws.kotlin.sns

import aws.sdk.kotlin.services.sns.model.PublishBatchRequestEntry
import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty

private const val SNS_BATCH_MAX_SIZE: Int = 10

@PublishedApi
internal fun validatePublishBatchRequest(
    topicArn: String,
    entries: List<PublishBatchRequestEntry>,
) {
    topicArn.requireNotBlank("topicArn")
    entries.requireNotEmpty("entries")
    entries.size.requireLe(SNS_BATCH_MAX_SIZE) { "entries must contain at most $SNS_BATCH_MAX_SIZE items." }

    val ids = entries.map { entry ->
        entry.id.requireNotBlank("entry.id")
        entry.message.requireNotBlank("entry.message")
        entry.id
    }
    require(ids.size == ids.toSet().size) { "entries must have distinct ids." }
}
