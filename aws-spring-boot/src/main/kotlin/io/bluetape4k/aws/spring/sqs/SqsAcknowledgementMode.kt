package io.bluetape4k.aws.spring.sqs

/**
 * 리스너가 메시지 확인을 수행하는 방식을 결정합니다.
 */
enum class SqsAcknowledgementMode {
    /** 기존 호환 모드입니다. 확인 매개변수가 있으면 수동, 없으면 성공 시 자동 확인입니다. */
    INHERIT,

    /** 핸들러가 정상 반환한 메시지를 프레임워크가 확인합니다. */
    ON_SUCCESS,

    /** 핸들러가 확인 API를 직접 호출합니다. */
    MANUAL,
}
