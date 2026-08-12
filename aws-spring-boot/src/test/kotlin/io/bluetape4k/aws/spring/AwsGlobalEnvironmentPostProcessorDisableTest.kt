package io.bluetape4k.aws.spring

import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.aws.spring.parameterstore.ParameterStoreEnvironmentPostProcessor
import io.bluetape4k.aws.spring.parameterstore.ParameterStorePropertySourceLoader
import io.bluetape4k.aws.spring.s3.S3ConfigEnvironmentPostProcessor
import io.bluetape4k.aws.spring.s3.S3ConfigPropertySourceLoader
import io.bluetape4k.aws.spring.secretsmanager.SecretsManagerEnvironmentPostProcessor
import io.bluetape4k.aws.spring.secretsmanager.SecretsManagerPropertySourceLoader
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

class AwsGlobalEnvironmentPostProcessorDisableTest {

    @Test
    fun `global enabled keeps Secrets Manager bootstrap active`() {
        mockkObject(SecretsManagerPropertySourceLoader)
        try {
            every { SecretsManagerPropertySourceLoader.load(any()) } returns emptyList()

            val environment = environmentOf(
                "bluetape4k.aws.enabled" to true,
                "bluetape4k.aws.secrets-manager.enabled" to true,
                "bluetape4k.aws.secrets-manager.sources[0].name" to "app-secret",
                "bluetape4k.aws.secrets-manager.sources[0].secret-id" to "secret-id",
            )

            SecretsManagerEnvironmentPostProcessor().postProcessEnvironment(environment, SpringApplication())

            verify(exactly = 1) { SecretsManagerPropertySourceLoader.load(any()) }
        } finally {
            unmockkObject(SecretsManagerPropertySourceLoader)
        }
    }

    @Test
    fun `global enabled keeps S3 bootstrap active`() {
        mockkObject(S3ConfigPropertySourceLoader)
        try {
            every { S3ConfigPropertySourceLoader.load(any()) } returns emptyList()

            val environment = environmentOf(
                "bluetape4k.aws.enabled" to true,
                "bluetape4k.aws.s3.config.enabled" to true,
                "bluetape4k.aws.s3.config.sources[0].name" to "app-config",
                "bluetape4k.aws.s3.config.sources[0].bucket" to "config-bucket",
                "bluetape4k.aws.s3.config.sources[0].key" to "application.yml",
            )

            S3ConfigEnvironmentPostProcessor().postProcessEnvironment(environment, SpringApplication())

            verify(exactly = 1) { S3ConfigPropertySourceLoader.load(any()) }
        } finally {
            unmockkObject(S3ConfigPropertySourceLoader)
        }
    }

    @Test
    fun `global enabled keeps Parameter Store bootstrap active`() {
        mockkObject(ParameterStorePropertySourceLoader)
        try {
            every { ParameterStorePropertySourceLoader.load(any()) } returns emptyList()

            val environment = environmentOf(
                "bluetape4k.aws.enabled" to true,
                "bluetape4k.aws.parameter-store.enabled" to true,
                "bluetape4k.aws.parameter-store.sources[0].name" to "app-parameters",
                "bluetape4k.aws.parameter-store.sources[0].path" to "/app",
            )

            ParameterStoreEnvironmentPostProcessor().postProcessEnvironment(environment, SpringApplication())

            verify(exactly = 1) { ParameterStorePropertySourceLoader.load(any()) }
        } finally {
            unmockkObject(ParameterStorePropertySourceLoader)
        }
    }

    @Test
    fun `global disabled skips Secrets Manager bootstrap even when source is configured`() {
        mockkObject(SecretsManagerPropertySourceLoader)
        try {
            every { SecretsManagerPropertySourceLoader.load(any()) } returns emptyList()

            val environment = environmentOf(
                "bluetape4k.aws.enabled" to false,
                "bluetape4k.aws.secrets-manager.enabled" to true,
                "bluetape4k.aws.secrets-manager.sources[0].name" to "app-secret",
                "bluetape4k.aws.secrets-manager.sources[0].secret-id" to "secret-id",
            )

            SecretsManagerEnvironmentPostProcessor().postProcessEnvironment(environment, SpringApplication())

            verify(exactly = 0) { SecretsManagerPropertySourceLoader.load(any()) }
            environment.propertySources.map { it.name } shouldNotContain "bluetape4k.aws.secrets-manager.app-secret"
        } finally {
            unmockkObject(SecretsManagerPropertySourceLoader)
        }
    }

    @Test
    fun `global disabled skips S3 bootstrap even when source is configured`() {
        mockkObject(S3ConfigPropertySourceLoader)
        try {
            every { S3ConfigPropertySourceLoader.load(any()) } returns emptyList()

            val environment = environmentOf(
                "bluetape4k.aws.enabled" to false,
                "bluetape4k.aws.s3.config.enabled" to true,
                "bluetape4k.aws.s3.config.sources[0].name" to "app-config",
                "bluetape4k.aws.s3.config.sources[0].bucket" to "config-bucket",
                "bluetape4k.aws.s3.config.sources[0].key" to "application.yml",
            )

            S3ConfigEnvironmentPostProcessor().postProcessEnvironment(environment, SpringApplication())

            verify(exactly = 0) { S3ConfigPropertySourceLoader.load(any()) }
            environment.propertySources.map { it.name } shouldNotContain "bluetape4k.aws.s3.config.app-config"
        } finally {
            unmockkObject(S3ConfigPropertySourceLoader)
        }
    }

    @Test
    fun `global disabled skips Parameter Store bootstrap even when source is configured`() {
        mockkObject(ParameterStorePropertySourceLoader)
        try {
            every { ParameterStorePropertySourceLoader.load(any()) } returns emptyList()

            val environment = environmentOf(
                "bluetape4k.aws.enabled" to false,
                "bluetape4k.aws.parameter-store.enabled" to true,
                "bluetape4k.aws.parameter-store.sources[0].name" to "app-parameters",
                "bluetape4k.aws.parameter-store.sources[0].path" to "/app",
            )

            ParameterStoreEnvironmentPostProcessor().postProcessEnvironment(environment, SpringApplication())

            verify(exactly = 0) { ParameterStorePropertySourceLoader.load(any()) }
            environment.propertySources.map { it.name } shouldNotContain "bluetape4k.aws.parameter-store.app-parameters"
        } finally {
            unmockkObject(ParameterStorePropertySourceLoader)
        }
    }

    private fun environmentOf(vararg values: Pair<String, Any>): StandardEnvironment =
        StandardEnvironment().apply {
            propertySources.addFirst(MapPropertySource("test", values.toMap()))
        }
}
