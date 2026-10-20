package io.bluetape4k.aws.ktor.s3

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.model.CreateBucketRequest
import aws.smithy.kotlin.runtime.net.url.Url as SmithyUrl
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.codec.Base58
import io.bluetape4k.javatimes.minutes
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.aws.kotlin.s3.s3ClientOf
import io.bluetape4k.testcontainers.aws.FlociServer
import io.ktor.http.Url
import org.junit.jupiter.api.Test

class S3KtorClientFlociTest {

    private companion object: KLoggingChannel() {
        private val floci by lazy { FlociServer.Launcher.floci }
    }

    @Test
    fun `advanced helpers round trip object through Floci S3`() = runSuspendIO {
        val bucket = "ktor-s3-${Base58.randomString(8).lowercase()}"
        val key = "config/application.conf"
        val payload = "ktor { deployment { port = 8080 } }"

        s3Client().use { s3Client ->
            s3Client.createBucket(CreateBucketRequest { this.bucket = bucket })

            s3KtorClientOf(
                region = floci.regionName,
                credentialsProvider = StaticCredentialsProvider {
                    accessKeyId = floci.awsAccessKey
                    secretAccessKey = floci.awsSecretKey
                },
                endpointOverride = Url(floci.awsEndpoint.toString()),
                addressingStyle = S3KtorAddressingStyle.Path,
                allowInsecureLocalHttpEndpoint = true,
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

                val presigned = s3.presignGetObject(bucket, key, 10.minutes())

                presigned.url.toString() shouldContain bucket
                presigned.url.toString() shouldContain "X-Amz-Signature"
            }
        }
    }

    private fun s3Client() = s3ClientOf(
        endpointUrl = SmithyUrl.parse(floci.awsEndpoint.toString()),
        region = floci.regionName,
        credentialsProvider = StaticCredentialsProvider {
            accessKeyId = floci.awsAccessKey
            secretAccessKey = floci.awsSecretKey
        },
        builder = { forcePathStyle = true },
    )
}
