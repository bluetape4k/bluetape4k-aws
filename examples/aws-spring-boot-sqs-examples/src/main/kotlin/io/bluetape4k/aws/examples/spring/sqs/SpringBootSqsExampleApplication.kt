package io.bluetape4k.aws.examples.spring.sqs

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/** AWS SQS/SNS facade와 listener 설정을 보여주는 Spring Boot 예제입니다. */
@SpringBootApplication
class SpringBootSqsExampleApplication

/** Spring Boot SQS/SNS 예제 애플리케이션을 실행합니다. */
fun main(args: Array<String>) {
    runApplication<SpringBootSqsExampleApplication>(*args)
}
