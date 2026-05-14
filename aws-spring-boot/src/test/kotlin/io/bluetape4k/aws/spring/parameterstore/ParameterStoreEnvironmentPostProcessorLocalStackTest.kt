@file:Suppress("DEPRECATION")

package io.bluetape4k.aws.spring.parameterstore

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.testcontainers.aws.LocalStackServer
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import org.awaitility.kotlin.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.ParameterType
import java.time.Duration
import java.util.UUID

class ParameterStoreEnvironmentPostProcessorLocalStackTest {

    companion object {
        private val localStack: LocalStackServer by lazy {
            LocalStackServer.Launcher.getLocalStack("ssm")
        }
        private var systemProperties: AutoCloseable? = null
        private var previousAccessKeyId: String? = null
        private var previousSecretAccessKey: String? = null

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
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
    fun `load recursive parameter path into Environment properties`() {
        val rootPath = "/config/${UUID.randomUUID()}"
        ssmClient().use { client ->
            client.putParameter {
                it.name("$rootPath/db/username")
                it.value("scott")
                it.type(ParameterType.STRING)
            }
            client.putParameter {
                it.name("$rootPath/db/password")
                it.value("tiger")
                it.type(ParameterType.SECURE_STRING)
            }
        }

        val environment = environmentOf(
            "bluetape4k.aws.parameter-store.region" to localStack.regionName,
            "bluetape4k.aws.parameter-store.endpoint-override" to localStack.awsEndpoint.toString(),
            "bluetape4k.aws.parameter-store.sources[0].name" to "app-parameters",
            "bluetape4k.aws.parameter-store.sources[0].path" to rootPath,
            "bluetape4k.aws.parameter-store.sources[0].prefix" to "app",
            "bluetape4k.aws.parameter-store.sources[0].with-decryption" to "true",
        )

        ParameterStoreEnvironmentPostProcessor().postProcessEnvironment(environment, SpringApplication())

        environment.getProperty("app.db.username") shouldBeEqualTo "scott"
        environment.getProperty("app.db.password") shouldBeEqualTo "tiger"
    }

    @Test
    fun `skip lookup when no parameter sources are configured`() {
        val environment = environmentOf("bluetape4k.aws.parameter-store.region" to localStack.regionName)

        ParameterStoreEnvironmentPostProcessor().postProcessEnvironment(environment, SpringApplication())

        environment.propertySources.map { it.name } shouldNotContain "bluetape4k.aws.parameter-store"
    }

    @Test
    fun `refresh configured parameter source after refresh interval`() {
        val rootPath = "/config/${UUID.randomUUID()}"
        ssmClient().use { client ->
            client.putParameter {
                it.name("$rootPath/db/password")
                it.value("initial")
                it.type(ParameterType.STRING)
            }
        }

        val environment = environmentOf(
            "bluetape4k.aws.parameter-store.region" to localStack.regionName,
            "bluetape4k.aws.parameter-store.endpoint-override" to localStack.awsEndpoint.toString(),
            "bluetape4k.aws.parameter-store.refresh-interval" to "10ms",
            "bluetape4k.aws.parameter-store.sources[0].name" to "app-parameters",
            "bluetape4k.aws.parameter-store.sources[0].path" to rootPath,
            "bluetape4k.aws.parameter-store.sources[0].prefix" to "app",
        )

        ParameterStoreEnvironmentPostProcessor().postProcessEnvironment(environment, SpringApplication())
        environment.getProperty("app.db.password") shouldBeEqualTo "initial"

        ssmClient().use { client ->
            client.putParameter {
                it.name("$rootPath/db/password")
                it.value("refreshed")
                it.type(ParameterType.STRING)
                it.overwrite(true)
            }
        }

        await.atMost(Duration.ofSeconds(10)).untilAsserted {
            environment.getProperty("app.db.password") shouldBeEqualTo "refreshed"
        }
    }

    private fun ssmClient(): SsmClient =
        SsmClient.builder()
            .credentialsProvider(localStack.getCredentialProvider())
            .region(Region.of(localStack.regionName))
            .endpointOverride(localStack.awsEndpoint)
            .build()

    private fun environmentOf(vararg values: Pair<String, Any>): StandardEnvironment =
        StandardEnvironment().apply {
            propertySources.addFirst(MapPropertySource("test", values.toMap()))
        }
}
