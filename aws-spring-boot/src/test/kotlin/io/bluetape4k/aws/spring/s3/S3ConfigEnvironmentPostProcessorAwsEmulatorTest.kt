package io.bluetape4k.aws.spring.s3

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.aws.spring.test.AwsSpringBootTestEmulator
import io.bluetape4k.codec.Base58
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import org.awaitility.kotlin.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.time.Duration

class S3ConfigEnvironmentPostProcessorAwsEmulatorTest {

    companion object {
        private val awsEmulator by lazy {
            AwsSpringBootTestEmulator.get("s3")
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
    fun `load S3 properties object into Environment properties`() {
        val bucket = "spring-s3-config-${Base58.randomString(8).lowercase()}"
        val key = "config/application.properties"
        s3Client().use { client ->
            client.createBucket { it.bucket(bucket) }
            client.putObject(
                { it.bucket(bucket).key(key).contentType("text/plain") },
                software.amazon.awssdk.core.sync.RequestBody.fromString("service.timeout=3s\nservice.retries=2\n"),
            )
        }

        val environment = environmentOf(
            "bluetape4k.aws.s3.config.region" to awsEmulator.regionName,
            "bluetape4k.aws.s3.config.endpoint-override" to awsEmulator.awsEndpoint.toString(),
            "bluetape4k.aws.s3.config.path-style-access-enabled" to "true",
            "bluetape4k.aws.s3.config.sources[0].name" to "app-s3-config",
            "bluetape4k.aws.s3.config.sources[0].bucket" to bucket,
            "bluetape4k.aws.s3.config.sources[0].key" to key,
            "bluetape4k.aws.s3.config.sources[0].prefix" to "app",
        )

        S3ConfigEnvironmentPostProcessor().postProcessEnvironment(environment, SpringApplication())

        environment.getProperty("app.service.timeout") shouldBeEqualTo "3s"
        environment.getProperty("app.service.retries") shouldBeEqualTo "2"
    }

    @Test
    fun `load S3 json object into Environment properties`() {
        val bucket = "spring-s3-config-${Base58.randomString(8).lowercase()}"
        val key = "config/application.json"
        s3Client().use { client ->
            client.createBucket { it.bucket(bucket) }
            client.putObject(
                { it.bucket(bucket).key(key).contentType("application/json") },
                software.amazon.awssdk.core.sync.RequestBody.fromString(
                    """{"service":{"name":"orders","limits":{"max":25}}}"""
                ),
            )
        }

        val environment = environmentOf(
            "bluetape4k.aws.s3.config.region" to awsEmulator.regionName,
            "bluetape4k.aws.s3.config.endpoint-override" to awsEmulator.awsEndpoint.toString(),
            "bluetape4k.aws.s3.config.path-style-access-enabled" to "true",
            "bluetape4k.aws.s3.config.sources[0].bucket" to bucket,
            "bluetape4k.aws.s3.config.sources[0].key" to key,
            "bluetape4k.aws.s3.config.sources[0].prefix" to "app",
        )

        S3ConfigEnvironmentPostProcessor().postProcessEnvironment(environment, SpringApplication())

        environment.getProperty("app.service.name") shouldBeEqualTo "orders"
        environment.getProperty("app.service.limits.max") shouldBeEqualTo "25"
    }

    @Test
    fun `skip lookup when no S3 config sources are configured`() {
        val environment = environmentOf("bluetape4k.aws.s3.config.region" to awsEmulator.regionName)

        S3ConfigEnvironmentPostProcessor().postProcessEnvironment(environment, SpringApplication())

        environment.propertySources.map { it.name } shouldNotContain "bluetape4k.aws.s3.config"
    }

    @Test
    fun `refresh configured S3 config source after refresh interval`() {
        val bucket = "spring-s3-config-${Base58.randomString(8).lowercase()}"
        val key = "config/application.properties"
        s3Client().use { client ->
            client.createBucket { it.bucket(bucket) }
            client.putObject(
                { it.bucket(bucket).key(key).contentType("text/plain") },
                software.amazon.awssdk.core.sync.RequestBody.fromString("feature.enabled=false\n"),
            )
        }

        val environment = environmentOf(
            "bluetape4k.aws.s3.config.region" to awsEmulator.regionName,
            "bluetape4k.aws.s3.config.endpoint-override" to awsEmulator.awsEndpoint.toString(),
            "bluetape4k.aws.s3.config.path-style-access-enabled" to "true",
            "bluetape4k.aws.s3.config.refresh-interval" to "10ms",
            "bluetape4k.aws.s3.config.sources[0].name" to "app-s3-config",
            "bluetape4k.aws.s3.config.sources[0].bucket" to bucket,
            "bluetape4k.aws.s3.config.sources[0].key" to key,
        )

        S3ConfigEnvironmentPostProcessor().postProcessEnvironment(environment, SpringApplication())
        environment.getProperty("feature.enabled") shouldBeEqualTo "false"

        s3Client().use { client ->
            client.putObject(
                { it.bucket(bucket).key(key).contentType("text/plain") },
                software.amazon.awssdk.core.sync.RequestBody.fromString("feature.enabled=true\n"),
            )
        }

        await.atMost(Duration.ofSeconds(10)).untilAsserted {
            environment.getProperty("feature.enabled") shouldBeEqualTo "true"
        }
    }

    private fun s3Client(): S3Client =
        S3Client.builder()
            .credentialsProvider(awsEmulator.getCredentialProvider())
            .region(Region.of(awsEmulator.regionName))
            .endpointOverride(awsEmulator.awsEndpoint)
            .serviceConfiguration {
                it.pathStyleAccessEnabled(true)
            }
            .build()

    private fun environmentOf(vararg values: Pair<String, Any>): StandardEnvironment =
        StandardEnvironment().apply {
            propertySources.addFirst(MapPropertySource("test", values.toMap()))
        }
}
