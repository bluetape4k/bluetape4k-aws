package io.bluetape4k.aws.examples.spring.dynamodb

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class SpringBootDynamoDbExampleApplication

fun main(args: Array<String>) {
    runApplication<SpringBootDynamoDbExampleApplication>(*args)
}
