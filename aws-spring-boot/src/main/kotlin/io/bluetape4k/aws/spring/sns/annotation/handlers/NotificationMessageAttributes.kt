package io.bluetape4k.aws.spring.sns.annotation.handlers

/** SNS envelope의 `MessageAttributes`를 `Map<String, SnsMessageAttribute>` snapshot으로 주입합니다. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class NotificationMessageAttributes
