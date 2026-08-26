package io.bluetape4k.aws.spring.sns

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.aws.spring.sns.annotation.endpoint.NotificationMessageMapping
import io.bluetape4k.aws.spring.sns.annotation.endpoint.NotificationSubscriptionMapping
import io.bluetape4k.aws.spring.sns.annotation.handlers.NotificationSubject
import io.bluetape4k.aws.spring.sns.handlers.NotificationStatus
import org.junit.jupiter.api.Test
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.method.HandlerMethod

class SnsHttpEndpointHandlerMethodValidatorTest {

    @Test
    fun `unsupported SNS parameter fails while handler methods are registered`() {
        val method = InvalidController::class.java.getDeclaredMethod("invalid", Int::class.javaPrimitiveType)
        val mapping = FakeHandlerMapping(mapOf("/invalid" to HandlerMethod(InvalidController(), method)))
        val validator = SnsHttpEndpointHandlerMethodValidator(
            support = SnsHttpMessageResolverSupport(
                SnsHttpEndpointProperties(verificationRequired = false, allowStructuralOnly = true),
            ),
            handlerMethods = { mapping.methods.values.associateBy { it.method } },
        )

        assertFailsWith<IllegalStateException> {
            validator.afterSingletonsInstantiated()
        }
    }

    @Test
    fun `notification mapping rejects confirmation status while handler methods are registered`() {
        val method = NotificationController::class.java.getDeclaredMethod("invalid", NotificationStatus::class.java)
        val mapping = FakeHandlerMapping(mapOf("/invalid" to HandlerMethod(NotificationController(), method)))
        val validator = SnsHttpEndpointHandlerMethodValidator(
            support = SnsHttpMessageResolverSupport(
                SnsHttpEndpointProperties(verificationRequired = false, allowStructuralOnly = true),
            ),
            handlerMethods = { mapping.methods.values.associateBy { it.method } },
        )

        assertFailsWith<IllegalStateException> {
            validator.afterSingletonsInstantiated()
        }
    }

    @Test
    fun `confirmation mapping rejects notification parameters while handler methods are registered`() {
        val method = ConfirmationController::class.java.getDeclaredMethod("invalid", String::class.java)
        val mapping = FakeHandlerMapping(mapOf("/invalid" to HandlerMethod(ConfirmationController(), method)))
        val validator = SnsHttpEndpointHandlerMethodValidator(
            support = SnsHttpMessageResolverSupport(
                SnsHttpEndpointProperties(verificationRequired = false, allowStructuralOnly = true),
            ),
            handlerMethods = { mapping.methods.values.associateBy { it.method } },
        )

        assertFailsWith<IllegalStateException> {
            validator.afterSingletonsInstantiated()
        }
    }

    @Test
    fun `class-level notification mapping is included in registration validation`() {
        val method = ClassLevelNotificationController::class.java
            .getDeclaredMethod("invalid", NotificationStatus::class.java)
        val mapping = FakeHandlerMapping(
            mapOf("/invalid" to HandlerMethod(ClassLevelNotificationController(), method)),
        )
        val validator = SnsHttpEndpointHandlerMethodValidator(
            support = SnsHttpMessageResolverSupport(
                SnsHttpEndpointProperties(verificationRequired = false, allowStructuralOnly = true),
            ),
            handlerMethods = { mapping.methods.values.associateBy { it.method } },
        )

        assertFailsWith<IllegalStateException> {
            validator.afterSingletonsInstantiated()
        }
    }

    private class FakeHandlerMapping(val methods: Map<String, HandlerMethod>) {
        fun getHandlerMethods(): Map<String, HandlerMethod> = methods
    }

    private class InvalidController {
        @NotificationMessageMapping(path = ["/invalid"])
        fun invalid(@NotificationSubject subject: Int) {
            subject.hashCode()
        }
    }

    private class NotificationController {
        @NotificationMessageMapping(path = ["/invalid"])
        fun invalid(status: NotificationStatus) {
            status.hashCode()
        }
    }

    private class ConfirmationController {
        @NotificationSubscriptionMapping(path = ["/invalid"])
        fun invalid(@NotificationSubject subject: String) {
            subject.hashCode()
        }
    }

    @NotificationMessageMapping
    private class ClassLevelNotificationController {
        @RequestMapping(path = ["/invalid"])
        fun invalid(status: NotificationStatus) {
            status.hashCode()
        }
    }
}
