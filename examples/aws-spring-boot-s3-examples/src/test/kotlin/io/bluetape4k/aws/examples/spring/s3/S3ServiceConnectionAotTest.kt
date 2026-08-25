package io.bluetape4k.aws.examples.spring.s3

import io.bluetape4k.testcontainers.aws.FlociServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.testcontainers.junit.jupiter.Container

/** AOT 전용 선언 계약이며 이 소스는 의도적으로 Testcontainers extension을 사용하지 않습니다. */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [AwsServiceConnectionAotConfiguration::class])
class S3ServiceConnectionAotTest {

    @Test
    fun declarationIsAotVisible() = Unit

    companion object {
        @JvmField
        @Container
        @ServiceConnection(name = "s3")
        val floci: FlociServer = FlociServer()
    }
}

/** 이름 없는 전체 서비스 opt-in 계약이며 이 소스는 Docker를 시작하지 않습니다. */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [AwsServiceConnectionAotConfiguration::class])
class AllAwsServicesConnectionAotTest {

    @Test
    fun declarationIsAotVisible() = Unit

    companion object {
        @JvmField
        @Container
        @ServiceConnection
        val allServices: FlociServer = FlociServer()
    }
}

@TestConfiguration(proxyBeanMethods = false)
class AwsServiceConnectionAotConfiguration
