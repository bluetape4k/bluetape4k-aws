package io.bluetape4k.aws.examples.spring.sqs

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class SpringBootSqsExampleApplication

fun main(args: Array<String>) {
    runApplication<SpringBootSqsExampleApplication>(*args)
}
