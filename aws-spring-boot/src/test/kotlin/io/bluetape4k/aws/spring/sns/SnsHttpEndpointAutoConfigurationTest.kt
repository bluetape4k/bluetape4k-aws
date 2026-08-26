package io.bluetape4k.aws.spring.sns

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.annotation.ReflectiveRuntimeHintsRegistrar
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.ImportRuntimeHints
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping as WebFluxRequestMappingHandlerMapping
import io.bluetape4k.aws.spring.sns.annotation.endpoint.NotificationMessageMapping
import io.bluetape4k.aws.spring.sns.annotation.endpoint.NotificationSubscriptionMapping
import io.bluetape4k.aws.spring.sns.annotation.endpoint.NotificationUnsubscribeConfirmationMapping
import io.bluetape4k.aws.spring.sns.annotation.handlers.NotificationMessage
import io.bluetape4k.aws.spring.sns.annotation.handlers.NotificationMessageAttributes
import io.bluetape4k.aws.spring.sns.annotation.handlers.NotificationRawMessage
import io.bluetape4k.aws.spring.sns.annotation.handlers.NotificationSubject
import io.bluetape4k.aws.spring.sns.handlers.NotificationStatus

class SnsHttpEndpointAutoConfigurationTest {

    @Test
    fun `MVC auto configuration registers resolver filter and properties`() {
        WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SnsHttpEndpointWebMvcAutoConfiguration::class.java))
            .withPropertyValues(
                "bluetape4k.aws.sns.http-endpoints.verification-required=false",
                "bluetape4k.aws.sns.http-endpoints.allow-structural-only=true",
            )
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(SnsMvcHttpMessageArgumentResolver::class.java) shouldHaveSize 1
                context.getBeansOfType(SnsHttpMessageServletFilter::class.java) shouldHaveSize 1
                context.getBeansOfType(SnsHttpEndpointProperties::class.java) shouldHaveSize 1
            }
    }

    @Test
    fun `WebFlux auto configuration registers resolver filter and properties`() {
        ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SnsHttpEndpointWebFluxAutoConfiguration::class.java))
            .withPropertyValues(
                "bluetape4k.aws.sns.http-endpoints.verification-required=false",
                "bluetape4k.aws.sns.http-endpoints.allow-structural-only=true",
            )
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(SnsWebFluxHttpMessageArgumentResolver::class.java) shouldHaveSize 1
                context.getBeansOfType(SnsHttpMessageWebFilter::class.java) shouldHaveSize 1
                context.getBeansOfType(SnsHttpEndpointProperties::class.java) shouldHaveSize 1
            }
    }

    @Test
    fun `endpoint and SNS switches disable both web adapters`() {
        WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SnsHttpEndpointWebMvcAutoConfiguration::class.java))
            .withPropertyValues(
                "bluetape4k.aws.sns.enabled=false",
                "bluetape4k.aws.sns.http-endpoints.verification-required=false",
                "bluetape4k.aws.sns.http-endpoints.allow-structural-only=true",
            )
            .run { context ->
                context.getBeansOfType(SnsMvcHttpMessageArgumentResolver::class.java) shouldHaveSize 0
                context.getBeansOfType(SnsHttpMessageServletFilter::class.java) shouldHaveSize 0
            }

        ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SnsHttpEndpointWebFluxAutoConfiguration::class.java))
            .withPropertyValues(
                "bluetape4k.aws.sns.http-endpoints.enabled=false",
                "bluetape4k.aws.sns.http-endpoints.verification-required=false",
                "bluetape4k.aws.sns.http-endpoints.allow-structural-only=true",
            )
            .run { context ->
                context.getBeansOfType(SnsWebFluxHttpMessageArgumentResolver::class.java) shouldHaveSize 0
                context.getBeansOfType(SnsHttpMessageWebFilter::class.java) shouldHaveSize 0
            }
    }

    @Test
    fun `optional web classpath backs off MVC auto configuration`() {
        WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SnsHttpEndpointWebMvcAutoConfiguration::class.java))
            .withClassLoader(FilteredClassLoader("org.springframework.web.servlet"))
            .run { context ->
                context.getBeansOfType(SnsMvcHttpMessageArgumentResolver::class.java) shouldHaveSize 0
        }
    }

    @Test
    fun `global AWS switch disables both web adapters`() {
        WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SnsHttpEndpointWebMvcAutoConfiguration::class.java))
            .withPropertyValues("bluetape4k.aws.enabled=false")
            .run { context ->
                context.getBeansOfType(SnsMvcHttpMessageArgumentResolver::class.java) shouldHaveSize 0
                context.getBeansOfType(SnsHttpMessageServletFilter::class.java) shouldHaveSize 0
            }

        ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SnsHttpEndpointWebFluxAutoConfiguration::class.java))
            .withPropertyValues("bluetape4k.aws.enabled=false")
            .run { context ->
                context.getBeansOfType(SnsWebFluxHttpMessageArgumentResolver::class.java) shouldHaveSize 0
                context.getBeansOfType(SnsHttpMessageWebFilter::class.java) shouldHaveSize 0
            }
    }

    @Test
    fun `MVC adapter starts without optional Jackson runtime`() {
        WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SnsHttpEndpointWebMvcAutoConfiguration::class.java))
            .withClassLoader(FilteredClassLoader("tools.jackson.databind"))
            .withPropertyValues(
                "bluetape4k.aws.sns.http-endpoints.verification-required=false",
                "bluetape4k.aws.sns.http-endpoints.allow-structural-only=true",
            )
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(SnsMvcHttpMessageArgumentResolver::class.java) shouldHaveSize 1
            }
    }

    @Test
    fun `MVC adapter backs off without SNS SDK runtime`() {
        WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SnsHttpEndpointWebMvcAutoConfiguration::class.java))
            .withClassLoader(FilteredClassLoader("software.amazon.awssdk.services.sns"))
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(SnsMvcHttpMessageArgumentResolver::class.java) shouldHaveSize 0
                context.getBeansOfType(SnsHttpMessageServletFilter::class.java) shouldHaveSize 0
            }
    }

    @Test
    fun `WebFlux adapter backs off without SNS SDK runtime`() {
        ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SnsHttpEndpointWebFluxAutoConfiguration::class.java))
            .withClassLoader(FilteredClassLoader("software.amazon.awssdk.services.sns"))
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(SnsWebFluxHttpMessageArgumentResolver::class.java) shouldHaveSize 0
                context.getBeansOfType(SnsHttpMessageWebFilter::class.java) shouldHaveSize 0
            }
    }

    @Test
    fun `MVC registration rejects confirmation parameter at context startup`() {
        WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SnsHttpEndpointWebMvcAutoConfiguration::class.java))
            .withUserConfiguration(InvalidMvcEndpointConfiguration::class.java)
            .run { context ->
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                context.startupFailure.shouldNotBeNull()
                messages shouldContain "NotificationStatus is only valid with confirmation mappings."
            }
    }

    @Test
    fun `WebFlux registration rejects notification parameter at context startup`() {
        ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SnsHttpEndpointWebFluxAutoConfiguration::class.java))
            .withUserConfiguration(InvalidWebFluxEndpointConfiguration::class.java)
            .run { context ->
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                context.startupFailure.shouldNotBeNull()
                messages shouldContain "Notification message parameters are only valid with NotificationMessageMapping."
            }
    }

    @Test
    fun `WebFlux adapter backs off without reactive web classes`() {
        ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SnsHttpEndpointWebFluxAutoConfiguration::class.java))
            .withClassLoader(FilteredClassLoader("org.springframework.web.reactive"))
            .run { context ->
                context.getBeansOfType(SnsWebFluxHttpMessageArgumentResolver::class.java) shouldHaveSize 0
            }
    }

    @Test
    fun `properties reject ambiguous security combinations`() {
        assertFailsWith<IllegalArgumentException> {
            SnsHttpEndpointProperties(verificationRequired = true, allowStructuralOnly = true)
        }
        assertFailsWith<IllegalArgumentException> {
            SnsHttpEndpointProperties(verificationRequired = false, allowStructuralOnly = false)
        }
        SnsHttpEndpointProperties(verificationRequired = false, allowStructuralOnly = true).enabled shouldBeEqualTo true
    }

    @Test
    fun `runtime hints preserve composed endpoint annotation`() {
        val hints = RuntimeHints()
        SnsHttpEndpointRuntimeHints().registerHints(hints, javaClass.classLoader)
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
            RuntimeHintsPredicates.reflection().onType(type).test(hints) shouldBeEqualTo true
        }
        SnsHttpEndpointWebMvcAutoConfiguration::class.java
            .getAnnotation(ImportRuntimeHints::class.java)
            .shouldNotBeNull()

        val controllerMethod = NativeController::class.java
            .getDeclaredMethod("notification", NativePayload::class.java)
        ReflectiveRuntimeHintsRegistrar().registerRuntimeHints(hints, NativeController::class.java)
        RuntimeHintsPredicates.reflection().onMethodInvocation(controllerMethod).test(hints) shouldBeEqualTo true
        RuntimeHintsPredicates.reflection().onType(NativePayload::class.java).test(hints) shouldBeEqualTo true

        val readValue = tools.jackson.databind.ObjectMapper::class.java.getMethod(
            "readValue",
            String::class.java,
            Class::class.java,
        )
        RuntimeHintsPredicates.reflection().onMethodInvocation(readValue).test(hints) shouldBeEqualTo true
    }

    @Configuration(proxyBeanMethods = false)
    private class InvalidMvcEndpointConfiguration {
        @Bean
        fun requestMappingHandlerMapping(): RequestMappingHandlerMapping = RequestMappingHandlerMapping()

        @Bean
        fun invalidMvcController(): InvalidMvcController = InvalidMvcController()
    }

    @RestController
    private class InvalidMvcController {
        @NotificationMessageMapping(path = ["/invalid-mvc"])
        fun invalid(status: NotificationStatus) {
            status.hashCode()
        }
    }

    @Configuration(proxyBeanMethods = false)
    private class InvalidWebFluxEndpointConfiguration {
        @Bean
        fun requestMappingHandlerMapping(): WebFluxRequestMappingHandlerMapping =
            WebFluxRequestMappingHandlerMapping()

        @Bean
        fun invalidWebFluxController(): InvalidWebFluxController = InvalidWebFluxController()
    }

    @RestController
    private class InvalidWebFluxController {
        @NotificationSubscriptionMapping(path = ["/invalid-webflux"])
        fun invalid(@NotificationSubject subject: String) {
            subject.hashCode()
        }
    }

    private class NativeController {
        @NotificationMessageMapping(path = ["/native"])
        fun notification(@NotificationMessage payload: NativePayload) {
            payload.hashCode()
        }
    }

    private class NativePayload {
        var id: String = ""
    }
}
