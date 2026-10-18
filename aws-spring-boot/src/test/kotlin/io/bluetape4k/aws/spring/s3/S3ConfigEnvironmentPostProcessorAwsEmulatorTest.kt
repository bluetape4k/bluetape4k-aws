package io.bluetape4k.aws.spring.s3

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.aws.s3.putAsString
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.system.SystemProperties
import io.bluetape4k.junit5.system.SystemProperty
import io.bluetape4k.logging.KLogging
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import kotlin.time.Duration.Companion.seconds

@SystemProperties(
    [
        SystemProperty("aws.accessKeyId", "test"),
        SystemProperty("aws.secretAccessKey", "test"),
    ]
)
class S3ConfigEnvironmentPostProcessorAwsEmulatorTest: AbstractS3Test() {

    companion object: KLogging()

    @Test
    fun `load S3 properties object into Environment properties`() {
        val bucket = "spring-s3-config-${Base58.randomString(8).lowercase()}"
        val key = "config/application.properties"
        val contents = "service.timeout=3s\nservice.retries=2\n"

        withS3Client { client ->
            client.createBucket { it.bucket(bucket) }
            client.putAsString(bucket, key, contents)
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
        val contents = """{"service":{"name":"orders","limits":{"max":25}}}"""

        withS3Client { client ->
            client.createBucket { it.bucket(bucket) }
            client.putAsString(bucket, key, contents, contentType = "application/json")
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
        val featureDisabled = "feature.enabled=false\n"

        withS3Client { client ->
            client.createBucket { it.bucket(bucket) }
            client.putAsString(bucket, key, featureDisabled)
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

        val featureEnabled = "feature.enabled=true\n"
        withS3Client { client ->
            client.putAsString(bucket, key, featureEnabled)
        }

        await atMost 3.seconds untilAsserted {
            environment.getProperty("feature.enabled") shouldBeEqualTo "true"
        }
    }
}
