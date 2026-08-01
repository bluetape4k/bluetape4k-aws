package io.bluetape4k.aws.spring.sns

/**
 * `AWS.SNS.SMS.SMSType` 메시지 속성에 사용할 Amazon SNS SMS 전송 타입입니다.
 */
enum class SnsSmsType(val attributeValue: String) {

    /**
     * 마케팅 알림처럼 중요하지 않고 비용에 최적화된 메시지입니다.
     */
    PROMOTIONAL("Promotional"),

    /**
     * 일회용 비밀번호나 계정 알림처럼 안정성에 최적화된 메시지입니다.
     */
    TRANSACTIONAL("Transactional"),
}
