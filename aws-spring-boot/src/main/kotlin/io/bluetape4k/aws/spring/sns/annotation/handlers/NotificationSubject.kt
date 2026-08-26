package io.bluetape4k.aws.spring.sns.annotation.handlers

/** SNS `Notification`의 선택적 `Subject`를 `String?`으로 주입합니다. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class NotificationSubject
