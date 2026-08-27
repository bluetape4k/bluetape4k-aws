package io.bluetape4k.aws.kinesis

import io.bluetape4k.support.requireNotBlank

internal const val MAX_KINESIS_IDENTIFIER_LENGTH: Int = 256
internal const val MAX_KINESIS_STREAM_NAME_LENGTH: Int = 128
internal const val MAX_KINESIS_SEQUENCE_LENGTH: Int = 1_024

internal fun String.requireKinesisIdentifier(
    name: String,
    maxLength: Int = MAX_KINESIS_IDENTIFIER_LENGTH,
): String {
    requireNotBlank(name)
    require(length <= maxLength) {
        "$name length must be <= $maxLength, but was $length"
    }
    require(none(Char::isISOControl)) {
        "$name must not contain ISO control characters"
    }
    return this
}

internal fun String.requireKinesisSequence(name: String = "sequenceNumber"): String =
    requireKinesisIdentifier(name, MAX_KINESIS_SEQUENCE_LENGTH)

internal fun String.requireKinesisStreamName(): String =
    requireKinesisIdentifier("streamName", MAX_KINESIS_STREAM_NAME_LENGTH)
