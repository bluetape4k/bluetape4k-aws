package io.bluetape4k.aws.spring.sns

import io.bluetape4k.aws.spring.sns.annotation.endpoint.NotificationMessageMapping
import io.bluetape4k.aws.spring.sns.annotation.endpoint.NotificationSubscriptionMapping
import io.bluetape4k.aws.spring.sns.annotation.endpoint.NotificationUnsubscribeConfirmationMapping
import io.bluetape4k.aws.spring.sns.annotation.handlers.NotificationMessage
import io.bluetape4k.aws.spring.sns.annotation.handlers.NotificationMessageAttributes
import io.bluetape4k.aws.spring.sns.annotation.handlers.NotificationRawMessage
import io.bluetape4k.aws.spring.sns.annotation.handlers.NotificationSubject
import io.bluetape4k.aws.spring.sns.handlers.NotificationStatus
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.ResponseStatus
import kotlin.reflect.KClass

class SnsHttpEndpointAnnotationTest {

    @Test
    fun `mapping annotations expose post header and no content`() {
        assertMapping(NotificationMessageMapping::class, "Notification")
        assertMapping(NotificationSubscriptionMapping::class, "SubscriptionConfirmation")
        assertMapping(NotificationUnsubscribeConfirmationMapping::class, "UnsubscribeConfirmation")
    }

    @Test
    fun `mapping annotations expose path alias`() {
        val mapping = NotificationMessageMapping(path = arrayOf("/notifications"))
        mapping.path.single() shouldBeEqualTo "/notifications"
    }

    @Test
    fun `handler annotations target value parameters`() {
        listOf(
            NotificationMessage::class,
            NotificationSubject::class,
            NotificationMessageAttributes::class,
            NotificationRawMessage::class,
        ).forEach { annotation ->
            annotation.java.getAnnotation(Target::class.java).allowedTargets
                .contains(AnnotationTarget.VALUE_PARAMETER).shouldBeTrue()
        }
        NotificationStatus::class.java.isInterface.shouldBeTrue()
    }

    private fun assertMapping(annotation: KClass<out Annotation>, type: String) {
        val requestMapping = annotation.java.getAnnotation(RequestMapping::class.java)
        requestMapping.method.toList() shouldBeEqualTo listOf(RequestMethod.POST)
        requestMapping.headers.toList() shouldBeEqualTo listOf("x-amz-sns-message-type=$type")
        annotation.java.getAnnotation(ResponseStatus::class.java).value shouldBeEqualTo HttpStatus.NO_CONTENT
    }
}
