package io.bluetape4k.aws.spring.sns

import io.bluetape4k.aws.spring.ConditionalOnAwsEnabled
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.REACTIVE
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ImportRuntimeHints
import org.springframework.web.reactive.config.WebFluxConfigurer
import org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping

/** Spring WebFlux 애플리케이션에 SNS HTTP adapter를 자동 설정합니다. */
@AutoConfiguration(after = [SnsAutoConfiguration::class])
@ConditionalOnAwsEnabled
@ConditionalOnWebApplication(type = REACTIVE)
@ConditionalOnClass(name = [
    "org.springframework.web.reactive.config.WebFluxConfigurer",
    "org.springframework.web.server.WebFilter",
    "software.amazon.awssdk.services.sns.model.ConfirmSubscriptionResponse",
])
@ConditionalOnProperty(prefix = "bluetape4k.aws.sns", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(
    prefix = SNS_HTTP_ENDPOINTS_PROPERTIES_PREFIX,
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(SnsHttpEndpointProperties::class)
@ImportRuntimeHints(SnsHttpEndpointRuntimeHints::class)
class SnsHttpEndpointWebFluxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun snsWebFluxHttpMessageArgumentResolver(
        properties: SnsHttpEndpointProperties,
        verifierProvider: ObjectProvider<SnsHttpMessageVerifier>,
        beanFactory: ListableBeanFactory,
        operationsProvider: ObjectProvider<SnsOperations>,
    ): SnsWebFluxHttpMessageArgumentResolver =
        SnsWebFluxHttpMessageArgumentResolver(
            SnsHttpMessageResolverSupport(
                properties = properties,
                verifierProvider = verifierProvider,
                objectMapper = findSnsObjectMapper(beanFactory),
                operations = operationsProvider.getIfAvailable(),
            )
        )

    @Bean
    @ConditionalOnMissingBean
    fun snsHttpMessageWebFilter(
        properties: SnsHttpEndpointProperties,
        verifierProvider: ObjectProvider<SnsHttpMessageVerifier>,
        beanFactory: ListableBeanFactory,
        operationsProvider: ObjectProvider<SnsOperations>,
    ): SnsHttpMessageWebFilter =
        SnsHttpMessageWebFilter(
            SnsHttpMessageResolverSupport(
                properties = properties,
                verifierProvider = verifierProvider,
                objectMapper = findSnsObjectMapper(beanFactory),
                operations = operationsProvider.getIfAvailable(),
            )
        )

    @Bean
    @ConditionalOnMissingBean(name = ["snsHttpEndpointWebFluxConfigurer"])
    fun snsHttpEndpointWebFluxConfigurer(
        resolver: SnsWebFluxHttpMessageArgumentResolver,
    ): WebFluxConfigurer = object : WebFluxConfigurer {
        override fun configureArgumentResolvers(configurer: ArgumentResolverConfigurer) {
            configurer.addCustomResolver(resolver)
        }
    }

    @Bean
    @ConditionalOnMissingBean(name = ["snsHttpEndpointWebFluxHandlerMethodValidator"])
    @ConditionalOnBean(RequestMappingHandlerMapping::class)
    fun snsHttpEndpointWebFluxHandlerMethodValidator(
        properties: SnsHttpEndpointProperties,
        handlerMapping: RequestMappingHandlerMapping,
    ): SmartInitializingSingleton = SnsHttpEndpointHandlerMethodValidator(
        support = SnsHttpMessageResolverSupport(properties = properties),
        handlerMethods = { handlerMapping.handlerMethods },
    )
}
