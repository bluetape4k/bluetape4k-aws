package io.bluetape4k.aws.sns

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.sns.model.PublishBatchRequest
import software.amazon.awssdk.services.sns.model.PublishBatchRequestEntry

private const val SNS_BATCH_MAX_SIZE: Int = 10

/** 이전에 inline된 소비자의 JVM 호출을 보존하는 호환성 bridge입니다. */
@Deprecated("Use publishBatch instead.", level = DeprecationLevel.WARNING)
fun validatePublishBatchRequest(
    topicArn: String,
    entries: List<PublishBatchRequestEntry>,
) {
    validatePublishBatchRequestInternal(topicArn, entries)
}

/** 이전에 inline된 소비자의 JVM 호출을 보존하는 호환성 bridge입니다. */
@Deprecated("Use publishBatch instead.", level = DeprecationLevel.WARNING)
fun PublishBatchRequest.validatePublishBatchRequest() {
    validatePublishBatchRequestInternal(topicArn(), publishBatchRequestEntries())
}

private fun validatePublishBatchRequestInternal(
    topicArn: String,
    entries: List<PublishBatchRequestEntry>,
) {
    topicArn.requireNotBlank("topicArn")
    require(entries.isNotEmpty()) { "entries must not be empty." }
    require(entries.size <= SNS_BATCH_MAX_SIZE) { "entries must contain at most 10 items." }

    val ids = entries.map { entry ->
        entry.id().requireNotBlank("entry.id")
        entry.message().requireNotBlank("entry.message")
        entry.id()
    }
    require(ids.size == ids.toSet().size) { "entries must have distinct ids." }
}
