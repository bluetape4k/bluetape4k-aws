@file:Suppress("DEPRECATION")

package io.bluetape4k.aws.spring.secretsmanager

import io.bluetape4k.testcontainers.aws.LocalStackServer
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import java.util.UUID

class SecretsManagerEnvironmentPostProcessorLocalStackTest {

    companion object {
        private val localStack: LocalStackServer = LocalStackServer().withServices("secretsmanager")
        private var systemProperties: AutoCloseable? = null
        private var previousAccessKeyId: String? = null
        private var previousSecretAccessKey: String? = null

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            localStack.start()
            localStack.writeToSystemProperties()
            systemProperties = localStack.registerSystemProperties()
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
            localStack.stop()
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
            "bluetape4k.aws.secrets-manager.region" to localStack.regionName,
            "bluetape4k.aws.secrets-manager.endpoint-override" to localStack.awsEndpoint.toString(),
            "bluetape4k.aws.secrets-manager.sources[0].name" to "app-secret",
            "bluetape4k.aws.secrets-manager.sources[0].secret-id" to secretId,
            "bluetape4k.aws.secrets-manager.sources[0].prefix" to "app",
        )

        SecretsManagerEnvironmentPostProcessor().postProcessEnvironment(environment, SpringApplication())

        assertThat(environment.getProperty("app.db.username")).isEqualTo("scott")
        assertThat(environment.getProperty("app.db.password")).isEqualTo("tiger")
        assertThat(environment.getProperty("app.feature", Boolean::class.java)).isTrue()
    }

    @Test
    fun `skip lookup when no secret sources are configured`() {
        val environment = environmentOf("bluetape4k.aws.secrets-manager.region" to localStack.regionName)

        SecretsManagerEnvironmentPostProcessor().postProcessEnvironment(environment, SpringApplication())

        assertThat(environment.propertySources.map { it.name })
            .doesNotContain("bluetape4k.aws.secrets-manager")
    }

    private fun secretsManagerClient(): SecretsManagerClient =
        SecretsManagerClient.builder()
            .credentialsProvider(localStack.getCredentialProvider())
            .region(Region.of(localStack.regionName))
            .endpointOverride(localStack.awsEndpoint)
            .build()

    private fun environmentOf(vararg values: Pair<String, Any>): StandardEnvironment =
        StandardEnvironment().apply {
            propertySources.addFirst(MapPropertySource("test", values.toMap()))
        }
}
