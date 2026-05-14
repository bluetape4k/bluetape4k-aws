package io.bluetape4k.aws.spring.secretsmanager

import io.bluetape4k.aws.spring.env.addAwsPropertySource
import io.bluetape4k.aws.spring.env.bindOrCreate
import io.bluetape4k.aws.spring.env.requireAwsSdkClass
import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment

/**
 * Loads configured AWS Secrets Manager values into the Spring Environment.
 *
 * ## Contract
 *
 * Runs before the application context is refreshed. It does nothing unless
 * `bluetape4k.aws.secrets-manager.sources` contains at least one source.
 */
class SecretsManagerEnvironmentPostProcessor: EnvironmentPostProcessor, Ordered {

    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE - 20

    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        val properties = environment.bindOrCreate<SecretsManagerProperties>("bluetape4k.aws.secrets-manager")
        if (!properties.enabled || properties.sources.isEmpty()) {
            return
        }

        requireAwsSdkClass(
            className = "software.amazon.awssdk.services.secretsmanager.SecretsManagerClient",
            dependencyNotation = "software.amazon.awssdk:secretsmanager",
            classLoader = javaClass.classLoader,
        )

        SecretsManagerPropertySourceLoader.load(properties)
            .forEach { propertySource ->
                environment.addAwsPropertySource(propertySource, properties.refreshInterval)
            }
    }
}
