package io.bluetape4k.aws.spring.sns.annotation.endpoint

import org.springframework.core.annotation.AliasFor
import org.springframework.aot.hint.annotation.Reflective
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.ResponseStatus

/** SNS `Notification` envelope를 매핑하고 HTTP 204로 수신을 확인합니다. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@RequestMapping(method = [RequestMethod.POST], headers = ["x-amz-sns-message-type=Notification"])
@ResponseStatus(HttpStatus.NO_CONTENT)
@Reflective(value = [SnsControllerMappingReflectiveProcessor::class])
annotation class NotificationMessageMapping(
    @get:AliasFor(annotation = RequestMapping::class, attribute = "path")
    val path: Array<String> = [],
)
