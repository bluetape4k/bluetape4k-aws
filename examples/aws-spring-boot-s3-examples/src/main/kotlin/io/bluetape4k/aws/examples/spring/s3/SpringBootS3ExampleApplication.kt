package io.bluetape4k.aws.examples.spring.s3

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * `aws-spring-boot` S3 자동설정을 사용하는 Spring Boot 4 예제 애플리케이션입니다.
 *
 * ## 동작/계약
 *
 * `S3Operations` 빈을 주입받는 [S3DocumentController]를 등록합니다. 실행 시
 * `bluetape4k.aws.s3.*` 설정으로 S3 client, presigner, coroutine template이 자동 구성됩니다.
 *
 * ```kotlin
 * fun main(args: Array<String>) {
 *     runApplication<SpringBootS3ExampleApplication>(*args)
 * }
 * ```
 */
@SpringBootApplication
class SpringBootS3ExampleApplication

/**
 * Spring Boot S3 예제 애플리케이션을 실행합니다.
 */
fun main(args: Array<String>) {
    runApplication<SpringBootS3ExampleApplication>(*args)
}
