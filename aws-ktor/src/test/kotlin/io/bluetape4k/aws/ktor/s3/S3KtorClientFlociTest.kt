package io.bluetape4k.aws.ktor.s3

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.testcontainers.aws.FlociServer
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import io.ktor.http.Url
import org.junit.jupiter.api.Test
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client

class S3KtorClientFlociTest {

    @Test
    fun `advanced helpers round trip object through Floci S3`() = runSuspendIO {
        val bucket = "ktor-s3-${Base58.randomString(8).lowercase()}"
        val key = "config/application.conf"
        val payload = "ktor { deployment { port = 8080 } }"

        s3Client().use { s3Client ->
            s3Client.createBucket { it.bucket(bucket) }

            s3KtorClientOf(
                region = floci.regionName,
                credentialsProvider = floci.getCredentialProvider(),
                endpointOverride = Url(floci.awsEndpoint.toString()),
                addressingStyle = S3KtorAddressingStyle.Path,
            ).use { s3 ->
                s3.putConfigObject(
                    bucket = bucket,
                    key = key,
                    text = payload,
                    metadata = mapOf("source" to "floci"),
                )

                val config = s3.getConfigObject(bucket, key)
                config.text shouldBeEqualTo payload
                config.metadata["source"] shouldBeEqualTo "floci"

                val presigned = s3.presignGetObject(bucket, key, java.time.Duration.ofMinutes(10))
                presigned.url.toString() shouldContain bucket
                presigned.url.toString() shouldContain "X-Amz-Signature"
            }
        }
    }

    private fun s3Client(): S3Client =
        S3Client.builder()
            .endpointOverride(floci.awsEndpoint)
            .region(Region.of(floci.regionName))
            .credentialsProvider(floci.getCredentialProvider())
            .forcePathStyle(true)
            .build()

    private companion object {
        private val floci by lazy { FlociServer.Launcher.floci }
    }
}
