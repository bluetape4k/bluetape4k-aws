package io.bluetape4k.aws.spring.sns

/**
 * Throughput scope for an SNS FIFO topic.
 *
 * ## Contract
 *
 * Provides safe enum values for the AWS SNS `FifoThroughputScope` topic
 * attribute.
 */
enum class SnsFifoThroughputScope(val attributeValue: String) {
    /**
     * Computes FIFO throughput at the whole-topic level.
     */
    TOPIC("Topic"),

    /**
     * Computes FIFO throughput at the message-group level.
     */
    MESSAGE_GROUP("MessageGroup"),
}
