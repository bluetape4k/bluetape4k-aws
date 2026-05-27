package io.bluetape4k.aws.spring.s3

import io.bluetape4k.aws.spring.env.addAwsPropertySource
import io.bluetape4k.aws.spring.env.bindOrCreate
import io.bluetape4k.aws.spring.env.requireAwsSdkClass
import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment

/**
 * Loads configured S3 objects into the Spring Environment.
 *
 * ## Contract
 *
 * Runs before the application context is refreshed. It does nothing unless
 * `bluetape4k.aws.s3.config.sources` contains at least one source.
 */
class S3ConfigEnvironmentPostProcessor: EnvironmentPostProcessor, Ordered {

    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE - 15

    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        val properties = environment.bindOrCreate<S3ConfigProperties>("bluetape4k.aws.s3.config")
        if (!properties.enabled || properties.sources.isEmpty()) {
            return
        }

        requireAwsSdkClass(
            className = "software.amazon.awssdk.services.s3.S3Client",
            dependencyNotation = "software.amazon.awssdk:s3",
            classLoader = javaClass.classLoader,
        )

        S3ConfigPropertySourceLoader.load(properties)
            .forEach { propertySource ->
                environment.addAwsPropertySource(propertySource, properties.refreshInterval)
            }
    }
}
