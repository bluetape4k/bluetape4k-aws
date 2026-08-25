package io.bluetape4k.aws.examples.spring.exposed

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * AWS Exposed auto-configuration을 보여주는 Spring Boot 애플리케이션입니다.
 */
@SpringBootApplication
class SpringBootExposedExampleApplication

/**
 * Spring Boot Exposed 예제 애플리케이션을 실행합니다.
 */
fun main(args: Array<String>) {
    runApplication<SpringBootExposedExampleApplication>(*args)
}
