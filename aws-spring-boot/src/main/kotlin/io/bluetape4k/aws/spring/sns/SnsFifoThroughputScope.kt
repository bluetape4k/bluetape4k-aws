package io.bluetape4k.aws.spring.sns

/**
 * SNS FIFO 주제의 처리량 범위입니다.
 *
 * ## 계약
 *
 * AWS SNS `FifoThroughputScope` 주제 속성에 사용할 안전한 enum 값을 제공합니다.
 */
enum class SnsFifoThroughputScope(val attributeValue: String) {
    /**
     * 전체 주제 수준에서 FIFO 처리량을 계산합니다.
     */
    TOPIC("Topic"),

    /**
     * 메시지 그룹 수준에서 FIFO 처리량을 계산합니다.
     */
    MESSAGE_GROUP("MessageGroup"),
}
