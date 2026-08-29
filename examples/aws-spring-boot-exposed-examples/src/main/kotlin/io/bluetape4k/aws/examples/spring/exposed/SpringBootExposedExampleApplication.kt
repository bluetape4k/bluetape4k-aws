package io.bluetape4k.aws.examples.spring.exposed

import io.bluetape4k.spring.data.exposed.jdbc.repository.config.EnableExposedJdbcRepositories
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import javax.sql.DataSource

/**
 * AWS Exposed auto-configuration을 보여주는 Spring Boot 애플리케이션입니다.
 */
@SpringBootApplication
@EnableExposedJdbcRepositories(basePackages = ["io.bluetape4k.aws.examples.spring.exposed"])
@EnableTransactionManagement
class SpringBootExposedExampleApplication {

    /**
     * Spring Data Exposed repository가 호출자 소유 transaction을 연결할 manager입니다.
     */
    @Bean("springTransactionManager")
    fun springTransactionManager(dataSource: DataSource): PlatformTransactionManager =
        SpringTransactionManager(dataSource, DatabaseConfig {}, false)
}

/**
 * Spring Boot Exposed 예제 애플리케이션을 실행합니다.
 */
fun main(args: Array<String>) {
    runApplication<SpringBootExposedExampleApplication>(*args)
}
