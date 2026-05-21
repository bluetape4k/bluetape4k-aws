package io.bluetape4k.aws.spring.secretsmanager

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.aws.spring.test.AwsSpringBootTestEmulator
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import org.awaitility.kotlin.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import java.time.Duration
import java.util.UUID

class SecretsManagerEnvironmentPostProcessorAwsEmulatorTest {

    companion object {
        private val awsEmulator by lazy {
            AwsSpringBootTestEmulator.get("secretsmanager")
        }
        private var systemProperties: AutoCloseable? = null
        private var previousAccessKeyId: String? = null
        private var previousSecretAccessKey: String? = null

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            systemProperties = awsEmulator.registerSystemProperties()
            previousAccessKeyId = System.getProperty("aws.accessKeyId")
            previousSecretAccessKey = System.getProperty("aws.secretAccessKey")
            System.setProperty("aws.accessKeyId", "test")
            System.setProperty("aws.secretAccessKey", "test")
        }

        @JvmStatic
        @AfterAll
        fun afterAll() {
            restoreSystemProperty("aws.accessKeyId", previousAccessKeyId)
            restoreSystemProperty("aws.secretAccessKey", previousSecretAccessKey)
            systemProperties?.close()
        }

        private fun restoreSystemProperty(name: String, value: String?) {
            if (value == null) {
                System.clearProperty(name)
            } else {
                System.setProperty(name, value)
            }
        }
    }

    @Test
    fun `load JSON secret into Environment properties`() {
        val secretId = "secret-${UUID.randomUUID()}"
        secretsManagerClient().use { client ->
            client.createSecret {
                it.name(secretId)
                it.secretString("""{"db":{"username":"scott","password":"tiger"},"feature":true}""")
            }
        }

        val environment = environmentOf(
            "bluetape4k.aws.secrets-manager.region" to awsEmulator.regionName,
            "bluetape4k.aws.secrets-manager.endpoint-override" to awsEmulator.awsEndpoint.toString(),
            "bluetape4k.aws.secrets-manager.sources[0].name" to "app-secret",
            "bluetape4k.aws.secrets-manager.sources[0].secret-id" to secretId,
            "bluetape4k.aws.secrets-manager.sources[0].prefix" to "app",
        )

        SecretsManagerEnvironmentPostProcessor().postProcessEnvironment(environment, SpringApplication())

        environment.getProperty("app.db.username") shouldBeEqualTo "scott"
        environment.getProperty("app.db.password") shouldBeEqualTo "tiger"
        environment.getProperty("app.feature", Boolean::class.java) shouldBeEqualTo true
    }

    @Test
    fun `skip lookup when no secret sources are configured`() {
        val environment = environmentOf("bluetape4k.aws.secrets-manager.region" to awsEmulator.regionName)

        SecretsManagerEnvironmentPostProcessor().postProcessEnvironment(environment, SpringApplication())

        environment.propertySources.map { it.name } shouldNotContain "bluetape4k.aws.secrets-manager"
    }

    @Test
    fun `refresh configured secret source after refresh interval`() {
        val secretId = "secret-${UUID.randomUUID()}"
        secretsManagerClient().use { client ->
            client.createSecret {
                it.name(secretId)
                it.secretString("""{"db":{"password":"initial"}}""")
            }
        }

        val environment = environmentOf(
            "bluetape4k.aws.secrets-manager.region" to awsEmulator.regionName,
            "bluetape4k.aws.secrets-manager.endpoint-override" to awsEmulator.awsEndpoint.toString(),
            "bluetape4k.aws.secrets-manager.refresh-interval" to "10ms",
            "bluetape4k.aws.secrets-manager.sources[0].name" to "app-secret",
            "bluetape4k.aws.secrets-manager.sources[0].secret-id" to secretId,
            "bluetape4k.aws.secrets-manager.sources[0].prefix" to "app",
        )

        SecretsManagerEnvironmentPostProcessor().postProcessEnvironment(environment, SpringApplication())
        environment.getProperty("app.db.password") shouldBeEqualTo "initial"

        secretsManagerClient().use { client ->
            client.updateSecret {
                it.secretId(secretId)
                it.secretString("""{"db":{"password":"refreshed"}}""")
            }
        }

        await.atMost(Duration.ofSeconds(10)).untilAsserted {
            environment.getProperty("app.db.password") shouldBeEqualTo "refreshed"
        }
    }

    private fun secretsManagerClient(): SecretsManagerClient =
        SecretsManagerClient.builder()
            .credentialsProvider(awsEmulator.getCredentialProvider())
            .region(Region.of(awsEmulator.regionName))
            .endpointOverride(awsEmulator.awsEndpoint)
            .build()

    private fun environmentOf(vararg values: Pair<String, Any>): StandardEnvironment =
        StandardEnvironment().apply {
            propertySources.addFirst(MapPropertySource("test", values.toMap()))
        }
}
