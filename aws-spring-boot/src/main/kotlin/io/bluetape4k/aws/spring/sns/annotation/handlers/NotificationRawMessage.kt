package io.bluetape4k.aws.spring.sns.annotation.handlers

/** 파싱·검증된 SNS HTTP envelope인 `SnsHttpMessage`를 주입합니다. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class NotificationRawMessage
