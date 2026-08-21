package io.bluetape4k.aws.spring.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.aws.spring.parameterstore.ParameterStoreConfigDataLoader
import io.bluetape4k.aws.spring.parameterstore.ParameterStoreConfigDataLocationResolver
import io.bluetape4k.aws.spring.s3.S3ConfigDataLoader
import io.bluetape4k.aws.spring.s3.S3ConfigDataLocationResolver
import io.bluetape4k.aws.spring.secretsmanager.SecretsManagerConfigDataLoader
import io.bluetape4k.aws.spring.secretsmanager.SecretsManagerConfigDataLocationResolver
import io.bluetape4k.aws.spring.test.AwsSpringBootTestEmulator
import io.bluetape4k.codec.Base58
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.boot.bootstrap.DefaultBootstrapContext
import org.springframework.boot.context.config.ConfigDataLocation
import org.springframework.boot.context.config.ConfigDataLoaderContext
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.ParameterType
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient

/** Floci 우선으로 세 ConfigData backend의 실제 payload와 bootstrap client 공유를 검증합니다. */
@Suppress("UNCHECKED_CAST")
class AwsConfigDataEmulatorTest {

    companion object {
        private val emulator by lazy {
            AwsSpringBootTestEmulator.get("s3", "ssm", "secretsmanager")
        }
        private var systemProperties: AutoCloseable? = null
        private var previousAccessKeyId: String? = null
        private var previousSecretAccessKey: String? = null

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            systemProperties = emulator.registerSystemProperties()
            previousAccessKeyId = System.getProperty("aws.accessKeyId")
            previousSecretAccessKey = System.getProperty("aws.secretAccessKey")
            System.setProperty("aws.accessKeyId", "test")
            System.setProperty("aws.secretAccessKey", "test")
        }

        @JvmStatic
        @AfterAll
        fun afterAll() {
            restore("aws.accessKeyId", previousAccessKeyId)
            restore("aws.secretAccessKey", previousSecretAccessKey)
            systemProperties?.close()
        }

        private fun restore(name: String, value: String?) {
            if (value == null) System.clearProperty(name) else System.setProperty(name, value)
        }
    }

    @Test
    fun `Floci S3 ConfigData imports properties with prefix`() {
        val bucket = "config-${Base58.randomString(16).lowercase()}"
        val key = "application.properties"
        s3Client().use { client ->
            client.createBucket { it.bucket(bucket) }
            client.putObject(
                { it.bucket(bucket).key(key) },
                RequestBody.fromString("service.name=orders\nservice.retries=2\n"),
            )
        }

        val bootstrap = DefaultBootstrapContext()
        try {
            val binder = binderOf(
                "bluetape4k.aws.s3.config.region" to emulator.regionName,
                "bluetape4k.aws.s3.config.endpoint-override" to emulator.awsEndpoint.toString(),
                "bluetape4k.aws.s3.config.path-style-access-enabled" to true,
            )
            val resolver = S3ConfigDataLocationResolver(mockk(relaxed = true), binder, bootstrap)
            val resource = resolver.resolve(
                mockk(relaxed = true),
                ConfigDataLocation.of("aws-s3:/$bucket/$key?prefix=app&format=properties"),
            ).single()
            val data = S3ConfigDataLoader(mockk(relaxed = true), bootstrap)
                .load(loaderContext(bootstrap), resource)!!

            val values = data.propertySources.single().source as Map<String, Any>
            values["app.service.name"] shouldBeEqualTo "orders"
            values["app.service.retries"] shouldBeEqualTo "2"
        } finally {
            bootstrap.close(mockk(relaxed = true))
        }
    }

    @Test
    fun `Floci Parameter Store ConfigData imports recursive values`() {
        val path = "/config/${Base58.randomString(16)}"
        ssmClient().use { client ->
            client.putParameter {
                it.name("$path/db/username").value("scott").type(ParameterType.STRING)
            }
            client.putParameter {
                it.name("$path/db/password").value("tiger").type(ParameterType.SECURE_STRING)
            }
        }

        val bootstrap = DefaultBootstrapContext()
        try {
            val binder = binderOf(
                "bluetape4k.aws.parameter-store.region" to emulator.regionName,
                "bluetape4k.aws.parameter-store.endpoint-override" to emulator.awsEndpoint.toString(),
            )
            val resolver = ParameterStoreConfigDataLocationResolver(mockk(relaxed = true), binder, bootstrap)
            val resource = resolver.resolve(
                mockk(relaxed = true),
                ConfigDataLocation.of("aws-parameterstore:$path?prefix=app&recursive=true&withDecryption=true"),
            ).single()
            val data = ParameterStoreConfigDataLoader(mockk(relaxed = true), bootstrap)
                .load(loaderContext(bootstrap), resource)!!

            val values = data.propertySources.single().source as Map<String, Any>
            values["app.db.username"] shouldBeEqualTo "scott"
            values["app.db.password"] shouldBeEqualTo "tiger"
        } finally {
            bootstrap.close(mockk(relaxed = true))
        }
    }

    @Test
    fun `Floci Secrets Manager ConfigData imports JSON secret`() {
        val secretId = "config-secret-${Base58.randomString(16)}"
        secretsManagerClient().use { client ->
            client.createSecret {
                it.name(secretId).secretString("""{"db":{"username":"scott"},"feature":true}""")
            }
        }

        val bootstrap = DefaultBootstrapContext()
        try {
            val binder = binderOf(
                "bluetape4k.aws.secrets-manager.region" to emulator.regionName,
                "bluetape4k.aws.secrets-manager.endpoint-override" to emulator.awsEndpoint.toString(),
            )
            val resolver = SecretsManagerConfigDataLocationResolver(mockk(relaxed = true), binder, bootstrap)
            val resource = resolver.resolve(
                mockk(relaxed = true),
                ConfigDataLocation.of("optional:aws-secretsmanager:$secretId?prefix=app&format=json"),
            ).single()
            val data = SecretsManagerConfigDataLoader(mockk(relaxed = true), bootstrap)
                .load(loaderContext(bootstrap), resource)!!

            val values = data.propertySources.single().source as Map<String, Any>
            values["app.db.username"] shouldBeEqualTo "scott"
            values["app.feature"] shouldBeEqualTo true
        } finally {
            bootstrap.close(mockk(relaxed = true))
        }
    }

    private fun binderOf(vararg properties: Pair<String, Any>): Binder =
        Binder(MapConfigurationPropertySource(properties.toMap()))

    private fun loaderContext(bootstrap: DefaultBootstrapContext): ConfigDataLoaderContext = mockk {
        every { getBootstrapContext() } returns bootstrap
    }

    private fun s3Client(): S3Client = S3Client.builder()
        .credentialsProvider(emulator.getCredentialProvider())
        .region(Region.of(emulator.regionName))
        .endpointOverride(emulator.awsEndpoint)
        .serviceConfiguration { it.pathStyleAccessEnabled(true) }
        .build()

    private fun ssmClient(): SsmClient = SsmClient.builder()
        .credentialsProvider(emulator.getCredentialProvider())
        .region(Region.of(emulator.regionName))
        .endpointOverride(emulator.awsEndpoint)
        .build()

    private fun secretsManagerClient(): SecretsManagerClient = SecretsManagerClient.builder()
        .credentialsProvider(emulator.getCredentialProvider())
        .region(Region.of(emulator.regionName))
        .endpointOverride(emulator.awsEndpoint)
        .build()
}
