package io.bluetape4k.aws.spring.parameterstore

import io.bluetape4k.aws.spring.env.addAwsPropertySource
import io.bluetape4k.aws.spring.env.bindOrCreate
import io.bluetape4k.aws.spring.env.requireAwsSdkClass
import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment

/**
 * 구성된 AWS SSM Parameter Store 값을 Spring Environment에 로드합니다.
 *
 * ## 계약
 *
 * 애플리케이션 컨텍스트를 새로 고치기 전에 실행됩니다. `bluetape4k.aws.parameter-store.sources`에
 * 소스가 하나 이상 있지 않으면 아무 작업도 하지 않습니다.
 */
class ParameterStoreEnvironmentPostProcessor: EnvironmentPostProcessor, Ordered {

    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE - 10

    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        val properties = environment.bindOrCreate<ParameterStoreProperties>("bluetape4k.aws.parameter-store")
        if (!properties.enabled || properties.sources.isEmpty()) {
            return
        }

        requireAwsSdkClass(
            className = "software.amazon.awssdk.services.ssm.SsmClient",
            dependencyNotation = "software.amazon.awssdk:ssm",
            classLoader = javaClass.classLoader,
        )

        ParameterStorePropertySourceLoader.load(properties)
            .forEach { propertySource ->
                environment.addAwsPropertySource(propertySource, properties.refreshInterval)
            }
    }
}
