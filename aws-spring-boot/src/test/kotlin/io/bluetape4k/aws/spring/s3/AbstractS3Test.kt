package io.bluetape4k.aws.spring.s3

import io.bluetape4k.aws.spring.test.AwsSpringBootTestEmulator
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import org.junit.jupiter.api.TestInstance
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractS3Test {

    companion object: KLogging() {
        @JvmStatic
        protected val awsEmulator by lazy { AwsSpringBootTestEmulator.get("s3") }

        @JvmStatic
        protected fun s3Client(): S3Client = S3Client.builder()
            .credentialsProvider(awsEmulator.getCredentialProvider())
            .region(Region.of(awsEmulator.regionName))
            .endpointOverride(awsEmulator.awsEndpoint)
            .serviceConfiguration {
                it.pathStyleAccessEnabled(true)
            }
            .build()

        @JvmStatic
        protected fun environmentOf(vararg values: Pair<String, Any>): StandardEnvironment =
            StandardEnvironment().apply {
                propertySources.addFirst(MapPropertySource("test", values.toMap()))
            }
    }

    protected inline fun <T> withS3Client(block: (S3Client) -> T): T {
        return s3Client().use { block(it) }
    }
}
