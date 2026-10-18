package io.bluetape4k.aws.spring.parameterstore

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.aws.spring.test.AwsSpringBootTestEmulator
import io.bluetape4k.junit5.system.SystemProperties
import io.bluetape4k.junit5.system.SystemProperty
import io.bluetape4k.logging.KLogging
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
import org.testcontainers.utility.Base58
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.ParameterType
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SystemProperties(
    value = [
        SystemProperty("aws.accessKeyId", "test"),
        SystemProperty("aws.secretAccessKey", "test"),
    ]
)
class ParameterStoreEnvironmentPostProcessorAwsEmulatorTest {

    companion object: KLogging() {
        private val awsEmulator by lazy {
            AwsSpringBootTestEmulator.get("ssm")
        }
    }

    private var systemProperties: AutoCloseable? = null

    @BeforeAll
    fun beforeAll() {
        systemProperties = awsEmulator.registerSystemProperties()
    }

    @AfterAll
    fun afterAll() {
        systemProperties?.close()
    }

    @Test
    fun `load recursive parameter path into Environment properties`() {
        val rootPath = "/config/${Base58.randomString(12).lowercase()}"

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
            "bluetape4k.aws.parameter-store.region" to awsEmulator.regionName,
            "bluetape4k.aws.parameter-store.endpoint-override" to awsEmulator.awsEndpoint.toString(),
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
        val environment = environmentOf("bluetape4k.aws.parameter-store.region" to awsEmulator.regionName)

        ParameterStoreEnvironmentPostProcessor().postProcessEnvironment(environment, SpringApplication())

        environment.propertySources.map { it.name } shouldNotContain "bluetape4k.aws.parameter-store"
    }

    @Test
    fun `refresh configured parameter source after refresh interval`() {
        val rootPath = "/config/${Base58.randomString(12).lowercase()}"

        ssmClient().use { client ->
            client.putParameter {
                it.name("$rootPath/db/password")
                it.value("initial")
                it.type(ParameterType.STRING)
            }
        }

        val environment = environmentOf(
            "bluetape4k.aws.parameter-store.region" to awsEmulator.regionName,
            "bluetape4k.aws.parameter-store.endpoint-override" to awsEmulator.awsEndpoint.toString(),
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

        await atMost 10.seconds untilAsserted {
            environment.getProperty("app.db.password") shouldBeEqualTo "refreshed"
        }
    }

    private fun ssmClient(): SsmClient =
        SsmClient.builder()
            .credentialsProvider(awsEmulator.getCredentialProvider())
            .region(Region.of(awsEmulator.regionName))
            .endpointOverride(awsEmulator.awsEndpoint)
            .build()

    private fun environmentOf(vararg values: Pair<String, Any>): StandardEnvironment =
        StandardEnvironment().apply {
            propertySources.addFirst(MapPropertySource("test", values.toMap()))
        }
}
