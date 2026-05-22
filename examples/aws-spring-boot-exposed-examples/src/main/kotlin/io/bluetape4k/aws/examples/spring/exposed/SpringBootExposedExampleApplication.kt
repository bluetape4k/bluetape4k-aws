package io.bluetape4k.aws.examples.spring.exposed

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Spring Boot application that demonstrates the AWS Exposed auto-configuration.
 */
@SpringBootApplication
class SpringBootExposedExampleApplication

/**
 * Starts the Spring Boot Exposed example application.
 */
fun main(args: Array<String>) {
    runApplication<SpringBootExposedExampleApplication>(*args)
}
