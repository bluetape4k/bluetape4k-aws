package io.bluetape4k.aws.spring.parameterstore

import io.bluetape4k.aws.spring.env.addAwsPropertySource
import io.bluetape4k.aws.spring.env.bindOrCreate
import io.bluetape4k.aws.spring.env.requireAwsSdkClass
import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment

/**
 * Loads configured AWS SSM Parameter Store values into the Spring Environment.
 *
 * ## Contract
 *
 * Runs before the application context is refreshed. It does nothing unless
 * `bluetape4k.aws.parameter-store.sources` contains at least one source.
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
            .forEach { (name, values) ->
                environment.addAwsPropertySource(name, values)
            }
    }
}
