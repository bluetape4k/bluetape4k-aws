package io.bluetape4k.aws.spring.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.aws.spring.s3.S3ConfigDataLoader
import io.bluetape4k.aws.spring.s3.S3ConfigDataSdkAdapter
import io.bluetape4k.aws.spring.parameterstore.ParameterStoreConfigDataSdkAdapter
import io.bluetape4k.aws.spring.secretsmanager.SecretsManagerConfigDataSdkAdapter
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.Test
import org.springframework.boot.bootstrap.DefaultBootstrapContext
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext
import org.springframework.boot.context.config.ConfigDataLocation
import org.springframework.boot.context.config.ConfigDataLoaderContext
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.DefaultResourceLoader
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.ssm.SsmClient

class AwsConfigDataImportApplicationTest {

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `Boot ConfigData lifecycle binds comma imports and preserves later import precedence`() {
        val bootstrap = DefaultBootstrapContext()
        val environment = StandardEnvironment().apply {
            propertySources.addFirst(
                MapPropertySource(
                    "test-config",
                    mapOf(
                        "spring.config.import" to listOf(
                            "aws-s3:/bucket/first",
                            "aws-parameterstore:/application",
                            "aws-secretsmanager:prod",
                            "aws-s3:/bucket/second",
                        ).joinToString(","),
                    ),
                ),
            )
        }
        val s3Client = mockk<S3Client>(relaxed = true)
        val ssmClient = mockk<SsmClient>(relaxed = true)
        val secretsManagerClient = mockk<SecretsManagerClient>(relaxed = true)
        mockkObject(
            S3ConfigDataSdkAdapter,
            ParameterStoreConfigDataSdkAdapter,
            SecretsManagerConfigDataSdkAdapter,
        )
        try {
            every { S3ConfigDataSdkAdapter.create(any()) } returns s3Client
            every { ParameterStoreConfigDataSdkAdapter.create(any()) } returns ssmClient
            every { SecretsManagerConfigDataSdkAdapter.create(any()) } returns secretsManagerClient
            every { S3ConfigDataSdkAdapter.load(any(), any()) } answers {
                val resource = invocation.args[1] as AwsConfigDataResource
                val source = resource.location.source as AwsConfigDataSource.S3
                mapOf("app.value" to if (source.key == "second") "second" else "first")
            }
            every { ParameterStoreConfigDataSdkAdapter.load(any(), any()) } returns mapOf("app.parameter" to "ssm")
            every { SecretsManagerConfigDataSdkAdapter.load(any(), any()) } returns mapOf("app.secret" to "secret")

            ConfigDataEnvironmentPostProcessor.applyTo(
                environment,
                DefaultResourceLoader(),
                bootstrap,
                emptyList<String>(),
            )

            environment.getProperty("app.value") shouldBeEqualTo "second"
            environment.getProperty("app.parameter") shouldBeEqualTo "ssm"
            environment.getProperty("app.secret") shouldBeEqualTo "secret"
            environment.propertySources.map { it.name }
                .count { it.startsWith("bluetape4k.aws.configdata.s3.") } shouldBeEqualTo 2
        } finally {
            bootstrap.close(mockk(relaxed = true))
            unmockkObject(
                S3ConfigDataSdkAdapter,
                ParameterStoreConfigDataSdkAdapter,
                SecretsManagerConfigDataSdkAdapter,
            )
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `Boot ConfigData lifecycle binds YAML indexed imports`() {
        val bootstrap = DefaultBootstrapContext()
        val environment = StandardEnvironment().apply {
            propertySources.addFirst(
                MapPropertySource(
                    "test-yaml-config",
                    mapOf(
                        "spring.config.import[0]" to "aws-s3:/bucket/application",
                        "spring.config.import[1]" to "aws-parameterstore:/application",
                        "spring.config.import[2]" to "aws-secretsmanager:prod",
                    ),
                ),
            )
        }
        val s3Client = mockk<S3Client>(relaxed = true)
        val ssmClient = mockk<SsmClient>(relaxed = true)
        val secretsManagerClient = mockk<SecretsManagerClient>(relaxed = true)
        mockkObject(
            S3ConfigDataSdkAdapter,
            ParameterStoreConfigDataSdkAdapter,
            SecretsManagerConfigDataSdkAdapter,
        )
        try {
            every { S3ConfigDataSdkAdapter.create(any()) } returns s3Client
            every { ParameterStoreConfigDataSdkAdapter.create(any()) } returns ssmClient
            every { SecretsManagerConfigDataSdkAdapter.create(any()) } returns secretsManagerClient
            every { S3ConfigDataSdkAdapter.load(any(), any()) } returns mapOf("app.value" to "yaml-s3")
            every { ParameterStoreConfigDataSdkAdapter.load(any(), any()) } returns mapOf("app.parameter" to "yaml-ssm")
            every { SecretsManagerConfigDataSdkAdapter.load(any(), any()) } returns mapOf("app.secret" to "yaml-secret")

            ConfigDataEnvironmentPostProcessor.applyTo(
                environment,
                DefaultResourceLoader(),
                bootstrap,
                emptyList<String>(),
            )

            environment.getProperty("app.value") shouldBeEqualTo "yaml-s3"
            environment.getProperty("app.parameter") shouldBeEqualTo "yaml-ssm"
            environment.getProperty("app.secret") shouldBeEqualTo "yaml-secret"
        } finally {
            bootstrap.close(mockk(relaxed = true))
            unmockkObject(
                S3ConfigDataSdkAdapter,
                ParameterStoreConfigDataSdkAdapter,
                SecretsManagerConfigDataSdkAdapter,
            )
        }
    }

    @Test
    fun `properties and YAML list use the same backend and source semantics`() {
        val locations = listOf(
            "optional:aws-s3:/bucket/application.yml?prefix=app&format=yaml",
            "aws-parameterstore:/application?prefix=app&recursive=true&withDecryption=true",
            "optional:aws-secretsmanager:prod?prefix=app&format=json",
        ).map(ConfigDataLocation::of)

        val parsed = locations.map(AwsConfigDataLocationParser()::parse)

        parsed.map { it.backend } shouldBeEqualTo listOf(
            AwsConfigDataBackend.S3,
            AwsConfigDataBackend.PARAMETER_STORE,
            AwsConfigDataBackend.SECRETS_MANAGER,
        )
        parsed.map { it.optional } shouldBeEqualTo listOf(true, false, true)
        parsed.map { it.source.canonicalSource } shouldBeEqualTo
            listOf("/bucket/application.yml", "/application", "prod")
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `later ConfigData import overrides an earlier value`() {
        val bootstrap = mockk<ConfigurableBootstrapContext>(relaxed = true)
        val context = mockk<ConfigDataLoaderContext> {
            every { getBootstrapContext() } returns bootstrap
        }
        val first = AwsConfigDataResource.from(
            AwsConfigDataLocationParser().parse(ConfigDataLocation.of("aws-s3:/bucket/first")),
            disabled = false,
        )
        val second = AwsConfigDataResource.from(
            AwsConfigDataLocationParser().parse(ConfigDataLocation.of("aws-s3:/bucket/second")),
            disabled = false,
        )
        mockkObject(S3ConfigDataSdkAdapter)
        try {
            every { S3ConfigDataSdkAdapter.load(bootstrap, first) } returns mapOf("app.value" to "first")
            every { S3ConfigDataSdkAdapter.load(bootstrap, second) } returns mapOf("app.value" to "second")
            val loader = S3ConfigDataLoader(mockk(relaxed = true), bootstrap)
            val merged = linkedMapOf<String, Any>()
            listOf(first, second).forEach { resource ->
                val data = loader.load(context, resource)!!
                val source = data.propertySources.single().source as Map<String, Any>
                merged.putAll(source)
            }

            merged["app.value"] shouldBeEqualTo "second"
        } finally {
            unmockkObject(S3ConfigDataSdkAdapter)
        }
    }
}
