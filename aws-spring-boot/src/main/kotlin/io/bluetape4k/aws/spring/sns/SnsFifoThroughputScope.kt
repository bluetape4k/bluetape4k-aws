package io.bluetape4k.aws.spring.sns

/**
 * SNS FIFO topic 처리량 범위.
 *
 * AWS SNS `FifoThroughputScope` topic attribute에 전달할 값을 안전하게 표현합니다.
 */
enum class SnsFifoThroughputScope(val attributeValue: String) {
    /**
     * Topic 전체 단위로 FIFO 처리량을 계산합니다.
     */
    TOPIC("Topic"),

    /**
     * Message group 단위로 FIFO 처리량을 계산합니다.
     */
    MESSAGE_GROUP("MessageGroup"),
}
