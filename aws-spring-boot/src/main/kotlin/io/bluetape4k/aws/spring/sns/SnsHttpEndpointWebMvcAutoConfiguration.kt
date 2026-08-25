package io.bluetape4k.aws.spring.sns

import io.bluetape4k.aws.spring.ConditionalOnAwsEnabled
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ImportRuntimeHints
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** Spring MVC 애플리케이션에 SNS HTTP adapter를 자동 설정합니다. */
@AutoConfiguration(after = [SnsAutoConfiguration::class])
@ConditionalOnAwsEnabled
@ConditionalOnWebApplication(type = SERVLET)
@ConditionalOnClass(name = [
    "org.springframework.web.servlet.config.annotation.WebMvcConfigurer",
    "org.springframework.web.filter.OncePerRequestFilter",
    "jakarta.servlet.http.HttpServletRequest",
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
class SnsHttpEndpointWebMvcAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun snsMvcHttpMessageArgumentResolver(
        properties: SnsHttpEndpointProperties,
        verifierProvider: ObjectProvider<SnsHttpMessageVerifier>,
        beanFactory: ListableBeanFactory,
        operationsProvider: ObjectProvider<SnsOperations>,
    ): SnsMvcHttpMessageArgumentResolver =
        SnsMvcHttpMessageArgumentResolver(
            SnsHttpMessageResolverSupport(
                properties = properties,
                verifierProvider = verifierProvider,
                objectMapper = findSnsObjectMapper(beanFactory),
                operations = operationsProvider.getIfAvailable(),
            )
        )

    @Bean
    @ConditionalOnMissingBean
    fun snsHttpMessageServletFilter(
        properties: SnsHttpEndpointProperties,
        verifierProvider: ObjectProvider<SnsHttpMessageVerifier>,
        beanFactory: ListableBeanFactory,
        operationsProvider: ObjectProvider<SnsOperations>,
    ): SnsHttpMessageServletFilter =
        SnsHttpMessageServletFilter(
            SnsHttpMessageResolverSupport(
                properties = properties,
                verifierProvider = verifierProvider,
                objectMapper = findSnsObjectMapper(beanFactory),
                operations = operationsProvider.getIfAvailable(),
            )
        )

    @Bean
    @ConditionalOnMissingBean(name = ["snsHttpEndpointWebMvcConfigurer"])
    fun snsHttpEndpointWebMvcConfigurer(
        resolver: SnsMvcHttpMessageArgumentResolver,
    ): WebMvcConfigurer = object : WebMvcConfigurer {
        override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
            resolvers += resolver
        }
    }

    @Bean
    @ConditionalOnMissingBean(name = ["snsHttpEndpointMvcHandlerMethodValidator"])
    fun snsHttpEndpointMvcHandlerMethodValidator(
        properties: SnsHttpEndpointProperties,
    ): BeanPostProcessor = SnsHttpEndpointHandlerMethodValidator(
        support = SnsHttpMessageResolverSupport(properties = properties),
        handlerMappingClassName = "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping",
    )
}
