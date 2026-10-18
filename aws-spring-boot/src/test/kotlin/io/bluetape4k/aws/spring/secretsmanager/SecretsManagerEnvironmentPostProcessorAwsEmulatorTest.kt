package io.bluetape4k.aws.spring.secretsmanager

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.aws.spring.test.AwsSpringBootTestEmulator
import io.bluetape4k.junit5.system.SystemProperties
import io.bluetape4k.junit5.system.SystemProperty
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.SpringApplication
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.getProperty
import org.testcontainers.utility.Base58
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SystemProperties(
    [
        SystemProperty("aws.accessKeyId", "test"),
        SystemProperty("aws.secretAccessKey", "test"),
    ]
)
class SecretsManagerEnvironmentPostProcessorAwsEmulatorTest {

    companion object: KLogging() {
        private val awsEmulator by lazy {
            AwsSpringBootTestEmulator.get("secretsmanager")
        }
    }

    private var systemProperties: AutoCloseable? = null
    private var previousAccessKeyId: String? = null
    private var previousSecretAccessKey: String? = null

    private fun restoreSystemProperty(name: String, value: String?) {
        if (value == null) {
            System.clearProperty(name)
        } else {
            System.setProperty(name, value)
        }
    }

    @BeforeAll
    fun beforeAll() {
        systemProperties = awsEmulator.registerSystemProperties()
    }

    @AfterAll
    fun afterAll() {
        systemProperties?.close()
    }

    @Test
    fun `load JSON secret into Environment properties`() {
        val secretId = "secret-${Base58.randomString(12)}"
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

        environment.getProperty<String>("app.db.username") shouldBeEqualTo "scott"
        environment.getProperty<String>("app.db.password") shouldBeEqualTo "tiger"
        environment.getProperty<Boolean>("app.feature").shouldBeTrue()
    }

    @Test
    fun `skip lookup when no secret sources are configured`() {
        val environment = environmentOf("bluetape4k.aws.secrets-manager.region" to awsEmulator.regionName)
        SecretsManagerEnvironmentPostProcessor().postProcessEnvironment(environment, SpringApplication())

        log.debug { "environment=$environment" }
        environment.propertySources.map { it.name } shouldNotContain SecretsManagerProperties.PREFIX
    }

    @Test
    fun `refresh configured secret source after refresh interval`() {
        val secretId = "secret-${Base58.randomString(12)}"

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
        log.debug { "environment=$environment" }
        environment.getProperty<String>("app.db.password") shouldBeEqualTo "initial"

        secretsManagerClient().use { client ->
            client.updateSecret {
                it.secretId(secretId)
                it.secretString("""{"db":{"password":"refreshed"}}""")
            }
        }

        await atMost 10.seconds untilAsserted {
            environment.getProperty<String>("app.db.password") shouldBeEqualTo "refreshed"
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
