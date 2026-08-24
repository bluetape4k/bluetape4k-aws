package io.bluetape4k.aws.examples.spring.dynamodb

import io.bluetape4k.testcontainers.aws.FlociServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.testcontainers.junit.jupiter.Container

/** AOT-only declaration contract; this source deliberately has no Testcontainers extension. */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [AwsServiceConnectionAotConfiguration::class])
class DynamoDbServiceConnectionAotTest {

    @Test
    fun declarationIsAotVisible() = Unit

    companion object {
        @JvmField
        @Container
        @ServiceConnection(name = "dynamodb")
        val floci: FlociServer = FlociServer()
    }
}

@TestConfiguration(proxyBeanMethods = false)
class AwsServiceConnectionAotConfiguration
