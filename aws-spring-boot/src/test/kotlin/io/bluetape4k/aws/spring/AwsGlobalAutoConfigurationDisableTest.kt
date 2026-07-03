package io.bluetape4k.aws.spring

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.sqs.SqsAsyncClient

class AwsGlobalAutoConfigurationDisableTest {

    @Test
    fun `all imported AWS auto configurations are guarded by global enabled flag`() {
        importedAutoConfigurationClasses().forEach { autoConfiguration ->
            autoConfiguration.isAnnotationPresent(ConditionalOnAwsEnabled::class.java).shouldBeTrue()
        }
    }

    @Test
    fun `global enabled false disables core and service auto configurations`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(*importedAutoConfigurationClasses()))
            .withPropertyValues("bluetape4k.aws.enabled=false")
            .run { context ->
                context.startupFailure shouldBeEqualTo null
                context.getBeansOfType(AwsProperties::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(AwsCredentialsProvider::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(S3Client::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(SqsAsyncClient::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(DynamoDbClient::class.java).size shouldBeEqualTo 0
            }
    }

    private fun importedAutoConfigurationClasses(): Array<Class<*>> {
        val classLoader = Thread.currentThread().contextClassLoader
        val resource = classLoader
            .getResource("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
            ?: error("AutoConfiguration.imports not found")

        return resource.readText()
            .lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map(classLoader::loadClass)
            .toList()
            .toTypedArray()
    }
}
