package io.bluetape4k.aws.spring.sns

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.aws.spring.sns.annotation.endpoint.NotificationMessageMapping
import io.bluetape4k.aws.spring.sns.annotation.handlers.NotificationSubject
import org.junit.jupiter.api.Test
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
            handlerMappingClassName = FakeHandlerMapping::class.java.name,
        )

        assertFailsWith<IllegalStateException> {
            validator.postProcessAfterInitialization(mapping, "mapping")
        }
    }

    private class FakeHandlerMapping(private val methods: Map<String, HandlerMethod>) {
        fun getHandlerMethods(): Map<String, HandlerMethod> = methods
    }

    private class InvalidController {
        @NotificationMessageMapping(path = ["/invalid"])
        fun invalid(@NotificationSubject subject: Int) {
            subject.hashCode()
        }
    }
}
