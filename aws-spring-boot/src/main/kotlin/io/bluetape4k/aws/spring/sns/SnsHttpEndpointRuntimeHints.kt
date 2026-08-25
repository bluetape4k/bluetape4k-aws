package io.bluetape4k.aws.spring.sns

import io.bluetape4k.aws.spring.sns.annotation.endpoint.NotificationMessageMapping
import io.bluetape4k.aws.spring.sns.annotation.endpoint.NotificationSubscriptionMapping
import io.bluetape4k.aws.spring.sns.annotation.endpoint.NotificationUnsubscribeConfirmationMapping
import io.bluetape4k.aws.spring.sns.annotation.handlers.NotificationMessage
import io.bluetape4k.aws.spring.sns.annotation.handlers.NotificationMessageAttributes
import io.bluetape4k.aws.spring.sns.annotation.handlers.NotificationRawMessage
import io.bluetape4k.aws.spring.sns.annotation.handlers.NotificationSubject
import io.bluetape4k.aws.spring.sns.handlers.NotificationStatus
import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar

/** 소비자 AOT reflection에 SNS composed annotation과 status API를 등록합니다. */
class SnsHttpEndpointRuntimeHints : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        listOf(
            NotificationMessageMapping::class.java,
            NotificationSubscriptionMapping::class.java,
            NotificationUnsubscribeConfirmationMapping::class.java,
            NotificationMessage::class.java,
            NotificationSubject::class.java,
            NotificationMessageAttributes::class.java,
            NotificationRawMessage::class.java,
            NotificationStatus::class.java,
        ).forEach { type ->
            hints.reflection().registerType(
                type,
                MemberCategory.INTROSPECT_DECLARED_METHODS,
                MemberCategory.INVOKE_DECLARED_METHODS,
                MemberCategory.INTROSPECT_DECLARED_CONSTRUCTORS,
            )
        }
    }
}
