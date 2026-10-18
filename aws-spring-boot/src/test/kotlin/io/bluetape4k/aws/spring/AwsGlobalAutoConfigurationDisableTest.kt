package io.bluetape4k.aws.spring

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBeansOfType
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.sqs.SqsAsyncClient

class AwsGlobalAutoConfigurationDisableTest {

    companion object: KLogging()

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
                context.startupFailure.shouldBeNull()
                context.getBeansOfType<AwsProperties>().shouldBeEmpty()
                context.getBeansOfType<AwsCredentialsProvider>().shouldBeEmpty()
                context.getBeansOfType<S3Client>().shouldBeEmpty()
                context.getBeansOfType<SqsAsyncClient>().shouldBeEmpty()
                context.getBeansOfType<DynamoDbClient>().shouldBeEmpty()
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
