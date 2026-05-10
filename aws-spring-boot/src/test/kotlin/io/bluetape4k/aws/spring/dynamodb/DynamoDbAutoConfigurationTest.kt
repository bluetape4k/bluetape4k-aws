package io.bluetape4k.aws.spring.dynamodb

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient

class DynamoDbAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                DynamoDbAutoConfiguration::class.java,
            )
        )
        .withPropertyValues("bluetape4k.aws.dynamodb.region=us-east-1")

    @Test
    fun `register DynamoDB clients properties and resolver`() {
        contextRunner.run { context ->
            context.getBeansOfType(DynamoDbAsyncClient::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(DynamoDbEnhancedAsyncClient::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(DynamoDbProperties::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(DynamoDbTableNameResolver::class.java).size shouldBeEqualTo 1
        }
    }

    @Test
    fun `back off when DynamoDB auto configuration disabled`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.dynamodb.enabled=false")
            .run { context ->
                context.getBeansOfType(DynamoDbAsyncClient::class.java).isEmpty().shouldBeTrue()
                context.getBeansOfType(DynamoDbEnhancedAsyncClient::class.java).isEmpty().shouldBeTrue()
                context.getBeansOfType(DynamoDbTableNameResolver::class.java).isEmpty().shouldBeTrue()
            }
    }

    @Test
    fun `custom DynamoDB async client backs off default client`() {
        val customClient = mockk<DynamoDbAsyncClient>(relaxed = true)

        contextRunner
            .withBean(DynamoDbAsyncClient::class.java, { customClient })
            .run { context ->
                context.getBeansOfType(DynamoDbAsyncClient::class.java).size shouldBeEqualTo 1
                context.getBean(DynamoDbAsyncClient::class.java) shouldBeSameInstanceAs customClient
                context.getBeansOfType(DynamoDbEnhancedAsyncClient::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `custom enhanced client backs off default enhanced client`() {
        val customEnhancedClient = mockk<DynamoDbEnhancedAsyncClient>(relaxed = true)

        contextRunner
            .withBean(DynamoDbEnhancedAsyncClient::class.java, { customEnhancedClient })
            .run { context ->
                context.getBeansOfType(DynamoDbEnhancedAsyncClient::class.java).size shouldBeEqualTo 1
                context.getBean(DynamoDbEnhancedAsyncClient::class.java) shouldBeSameInstanceAs customEnhancedClient
            }
    }

    @Test
    fun `custom table name resolver backs off default resolver`() {
        val customResolver = DynamoDbTableNameResolver { "custom-$it" }

        contextRunner
            .withBean(DynamoDbTableNameResolver::class.java, { customResolver })
            .run { context ->
                context.getBeansOfType(DynamoDbTableNameResolver::class.java).size shouldBeEqualTo 1
                context.getBean(DynamoDbTableNameResolver::class.java).resolve("orders") shouldBeEqualTo "custom-orders"
            }
    }

    @Test
    fun `endpoint override requires region`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DynamoDbAutoConfiguration::class.java))
            .withBean(AwsCredentialsProvider::class.java, {
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))
            })
            .withPropertyValues("bluetape4k.aws.dynamodb.endpoint-override=http://localhost:4566")
            .run { context ->
                (context.startupFailure != null).shouldBeTrue()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                messages shouldContain "region is required"
            }
    }

    @Test
    fun `table prefix binds and resolver applies it`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.dynamodb.table-prefix=test-")
            .run { context ->
                context.getBean(DynamoDbProperties::class.java).tablePrefix shouldBeEqualTo "test-"
                context.getBean(DynamoDbTableNameResolver::class.java).resolve("orders") shouldBeEqualTo "test-orders"
            }
    }

    @Test
    fun `DynamoDB auto configuration backs off when DynamoDB SDK is absent`() {
        contextRunner
            .withClassLoader(FilteredClassLoader("software.amazon.awssdk.services.dynamodb"))
            .run { context ->
                context.getBeansOfType(DynamoDbAsyncClient::class.java).isEmpty().shouldBeTrue()
                context.getBeansOfType(DynamoDbEnhancedAsyncClient::class.java).isEmpty().shouldBeTrue()
            }
    }
}
