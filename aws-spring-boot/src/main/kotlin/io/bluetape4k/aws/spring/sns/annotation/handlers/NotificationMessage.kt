package io.bluetape4k.aws.spring.sns.annotation.handlers

/** 중첩된 SNS `Message`를 원문 또는 JSON 타입으로 변환해 주입합니다. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class NotificationMessage
