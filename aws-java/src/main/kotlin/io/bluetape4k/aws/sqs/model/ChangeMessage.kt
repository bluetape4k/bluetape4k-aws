package io.bluetape4k.aws.sqs.model

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityBatchRequestEntry

inline fun changeMessageVisibilityBatchRequestEntry(
    builder: ChangeMessageVisibilityBatchRequestEntry.Builder.() -> Unit,
): ChangeMessageVisibilityBatchRequestEntry =
    ChangeMessageVisibilityBatchRequestEntry.builder().apply(builder).build()


inline fun changeMessageVisibilityBatchRequestEntryOf(
    id: String,
    receiptHandle: String,
    visibilityTimeout: Int? = null,
    builder: ChangeMessageVisibilityBatchRequestEntry.Builder.() -> Unit = {},
): ChangeMessageVisibilityBatchRequestEntry =
    changeMessageVisibilityBatchRequestEntry {
        id(id.requireNotEmpty("id"))
        receiptHandle(receiptHandle.requireNotBlank("receiptHandle"))
        visibilityTimeout?.let { visibilityTimeout(it) }

        builder()
    }
