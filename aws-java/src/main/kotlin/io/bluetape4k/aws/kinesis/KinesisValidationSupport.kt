package io.bluetape4k.aws.kinesis

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotEmpty

@PublishedApi
internal const val MIN_KINESIS_SHARD_COUNT = 1

@PublishedApi
internal const val MIN_KINESIS_GET_RECORDS_LIMIT = 1

@PublishedApi
internal const val MAX_KINESIS_GET_RECORDS_LIMIT = 10_000

@PublishedApi
internal const val MAX_KINESIS_PUT_RECORDS_ENTRIES = 500

@PublishedApi
internal fun Int.validateKinesisShardCount(parameterName: String) {
    requireInRange(MIN_KINESIS_SHARD_COUNT, Int.MAX_VALUE, parameterName)
}

@PublishedApi
internal fun Int.validateKinesisGetRecordsLimit(parameterName: String) {
    requireInRange(MIN_KINESIS_GET_RECORDS_LIMIT, MAX_KINESIS_GET_RECORDS_LIMIT, parameterName)
}

@PublishedApi
internal fun validateKinesisPutRecordsEntries(size: Int, parameterName: String) {
    size.requireInRange(1, MAX_KINESIS_PUT_RECORDS_ENTRIES, parameterName)
}

@PublishedApi
internal fun <T> List<T>.validateKinesisPutRecordsEntries(parameterName: String) {
    requireNotEmpty(parameterName)
    validateKinesisPutRecordsEntries(size, parameterName)
}
