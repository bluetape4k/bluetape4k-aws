package io.bluetape4k.aws.sqs

import io.bluetape4k.support.requireInRange

@PublishedApi
internal const val MAX_SQS_BATCH_ENTRIES = 10

@PublishedApi
internal const val MIN_SQS_DELAY_SECONDS = 0

@PublishedApi
internal const val MAX_SQS_DELAY_SECONDS = 900

@PublishedApi
internal const val MIN_SQS_VISIBILITY_TIMEOUT_SECONDS = 0

@PublishedApi
internal const val MAX_SQS_VISIBILITY_TIMEOUT_SECONDS = 43_200

@PublishedApi
internal fun validateSqsBatchSize(size: Int, parameterName: String) {
    size.requireInRange(1, MAX_SQS_BATCH_ENTRIES, parameterName)
}

@PublishedApi
internal fun Int.validateSqsDelaySeconds(parameterName: String) {
    requireInRange(MIN_SQS_DELAY_SECONDS, MAX_SQS_DELAY_SECONDS, parameterName)
}

@PublishedApi
internal fun Int.validateSqsVisibilityTimeout(parameterName: String) {
    requireInRange(MIN_SQS_VISIBILITY_TIMEOUT_SECONDS, MAX_SQS_VISIBILITY_TIMEOUT_SECONDS, parameterName)
}
