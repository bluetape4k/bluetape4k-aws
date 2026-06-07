package io.bluetape4k.aws.spring.dynamodb

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.mockk.clearMocks
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.dax.ClusterDaxAsyncClient

class DynamoDbAutoConfigurationTest {

    private val customClient = mockk<DynamoDbAsyncClient>(relaxed = true)
    private val customEnhancedClient = mockk<DynamoDbEnhancedAsyncClient>(relaxed = true)

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                DynamoDbDaxAutoConfiguration::class.java,
                DynamoDbAutoConfiguration::class.java,
            )
        )
        .withPropertyValues("bluetape4k.aws.dynamodb.region=us-east-1")

    @BeforeEach
    fun resetMocks() {
        clearMocks(customClient, customEnhancedClient)
    }

    @Test
    fun `register DynamoDB clients properties and resolver`() {
        contextRunner.run { context ->
            context.getBeansOfType(DynamoDbAsyncClient::class.java).size shouldBeEqualTo 1
            (context.getBean(DynamoDbAsyncClient::class.java) !is ClusterDaxAsyncClient).shouldBeTrue()
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
        contextRunner
            .withBean(DynamoDbAsyncClient::class.java, { customClient })
            .run { context ->
                context.getBeansOfType(DynamoDbAsyncClient::class.java).size shouldBeEqualTo 1
                context.getBean(DynamoDbAsyncClient::class.java) shouldBeSameInstanceAs customClient
                context.getBeansOfType(DynamoDbEnhancedAsyncClient::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `custom DynamoDB async client backs off DAX client`() {
        contextRunner
            .withBean(DynamoDbAsyncClient::class.java, { customClient })
            .withPropertyValues(
                "bluetape4k.aws.dynamodb.dax.enabled=true",
                "bluetape4k.aws.dynamodb.dax.url=dax://orders-cache.example.com",
            )
            .run { context ->
                context.getBeansOfType(DynamoDbAsyncClient::class.java).size shouldBeEqualTo 1
                context.getBean(DynamoDbAsyncClient::class.java) shouldBeSameInstanceAs customClient
                context.getBeansOfType(DynamoDbEnhancedAsyncClient::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `custom enhanced client backs off default enhanced client`() {
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
    fun `DAX classpath absence keeps default DynamoDB client path`() {
        contextRunner
            .withClassLoader(FilteredClassLoader("software.amazon.dax"))
            .withPropertyValues(
                "bluetape4k.aws.dynamodb.dax.enabled=true",
                "bluetape4k.aws.dynamodb.dax.url=dax://orders-cache.example.com",
            )
            .run { context ->
                context.getBeansOfType(DynamoDbAsyncClient::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(DynamoDbEnhancedAsyncClient::class.java).size shouldBeEqualTo 1
                (context.getBean(DynamoDbAsyncClient::class.java) !is ClusterDaxAsyncClient).shouldBeTrue()
            }
    }

    @Test
    fun `DAX enabled requires URL`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.dynamodb.dax.enabled=true")
            .run { context ->
                (context.startupFailure != null).shouldBeTrue()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                messages shouldContain "dax.url is required"
            }
    }

    @Test
    fun `DAX enabled registers DAX async client and enhanced client`() {
        contextRunner
            .withTestAwsCredentials()
            .withPropertyValues(
                "bluetape4k.aws.dynamodb.dax.enabled=true",
                "bluetape4k.aws.dynamodb.dax.url=dax://orders-cache.example.com",
            )
            .run { context ->
                context.getBeansOfType(DynamoDbAsyncClient::class.java).size shouldBeEqualTo 1
                (context.getBean(DynamoDbAsyncClient::class.java) is ClusterDaxAsyncClient).shouldBeTrue()
                context.getBeansOfType(DynamoDbEnhancedAsyncClient::class.java).size shouldBeEqualTo 1
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

    private fun ApplicationContextRunner.withTestAwsCredentials(): ApplicationContextRunner =
        withBean(AwsCredentialsProvider::class.java, {
            StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))
        })
}
