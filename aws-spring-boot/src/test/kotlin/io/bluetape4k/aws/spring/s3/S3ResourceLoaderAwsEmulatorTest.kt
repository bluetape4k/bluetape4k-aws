package io.bluetape4k.aws.spring.s3

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.aws.s3.headObject
import io.bluetape4k.aws.s3.model.deleteBucketRequestOf
import io.bluetape4k.aws.s3.model.deleteObjectRequestOf
import io.bluetape4k.aws.s3.putAsString
import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.test.AwsSpringBootTestEmulator
import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.services.s3.S3Client

/**
 * Floci 우선으로 한 bucket의 exact/pattern Resource 경계를 검증한다.
 * 테스트가 만든 object와 bucket만 제거하며 공유 emulator를 중지하지 않는다.
 */
class S3ResourceLoaderAwsEmulatorTest {

    companion object: KLogging()

    @Test
    fun `exact and wildcard resources use one literal bucket`() {
        val emulator = AwsSpringBootTestEmulator.get("s3")
        val bucket = "issue-463-${Base58.randomString(10).lowercase()}"
        val keys = listOf(
            "config/application.json",
            "config/nested/application.json",
            "config/readme.txt",
            "other/application.json",
        )
        var primaryFailure: Throwable? = null
        var cleanupFailure: Throwable? = null

        contextRunner(emulator).run { context ->
            val client = context.getBean<S3Client>()
            try {
                client.createBucket { it.bucket(bucket) }
                keys.forEach { key ->
                    val contents = "$key\n"
                    client.putAsString(bucket, key, contents, Charsets.UTF_8, "application/json")
                }

                context.getResource("s3://$bucket/config/application.json")
                    .inputStream.bufferedReader().use {
                        it.readText()
                    } shouldBeEqualTo "config/application.json\n"

                val resolver = context.getBean<S3ResourcePatternResolver>("s3ResourcePatternResolver")

                resolver.getResources("s3://$bucket/config/*.json")
                    .map {
                        it.shouldBeInstanceOf<S3Resource>().location.key
                    } shouldBeEqualTo listOf("config/application.json")

                resolver.getResources("s3://$bucket/config/**/*.json")
                    .map {
                        it.shouldBeInstanceOf<S3Resource>().location.key
                    } shouldBeEqualTo listOf("config/application.json", "config/nested/application.json")

                resolver.getResources("s3://$bucket/config/**/*.json").size shouldBeEqualTo 2

                client.headObject(bucket, "config/application.json")
                    .sdkHttpResponse().isSuccessful.shouldBeTrue()

            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                try {
                    keys.forEach { key ->
                        client.deleteObject(deleteObjectRequestOf(bucket, key))
                    }
                    client.deleteBucket(deleteBucketRequestOf(bucket))
                } catch (cleanupError: Throwable) {
                    cleanupFailure = cleanupError
                    primaryFailure?.addSuppressed(cleanupError)
                }
            }
            if (primaryFailure == null) {
                cleanupFailure?.let { failure -> throw failure }
            }
        }
    }

    private fun contextRunner(emulator: io.bluetape4k.testcontainers.aws.AwsEmulatorServer): ApplicationContextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AwsAutoConfiguration::class.java,
                    S3AutoConfiguration::class.java,
                    S3ResourceAutoConfiguration::class.java,
                ),
            )
            .withBean(AwsCredentialsProvider::class.java, { emulator.getCredentialProvider() })
            .withPropertyValues(
                "bluetape4k.aws.s3.region=${emulator.regionName}",
                "bluetape4k.aws.s3.endpoint-override=${emulator.awsEndpoint}",
                "bluetape4k.aws.s3.path-style-access-enabled=true",
            )
}
