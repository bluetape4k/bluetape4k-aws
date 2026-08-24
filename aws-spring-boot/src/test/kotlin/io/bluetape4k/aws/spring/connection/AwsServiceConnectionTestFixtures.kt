package io.bluetape4k.aws.spring.connection

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.testcontainers.aws.AwsEmulatorServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client

@SpringBootConfiguration
@ImportAutoConfiguration(
    classes = [
        io.bluetape4k.aws.spring.AwsAutoConfiguration::class,
        io.bluetape4k.aws.spring.s3.S3AutoConfiguration::class,
    ],
)
class AwsServiceConnectionTestApplication

/** One-owner, one-bucket S3 fixture used by the ServiceConnection emulator lanes. */
internal object AwsServiceConnectionTestFixtures {

    fun roundTrip(client: S3Client, emulator: AwsEmulatorServer): S3RoundTripReceipt {
        val ownerToken = "owner-${Base58.randomString(10).lowercase()}"
        val bucket = "issue472-${ownerToken.replace('_', '-')}"
        val key = "$ownerToken/payload.txt"
        requireOwnedResource(ownerToken, bucket, key)

        var bucketCreated = false
        var objectCreated = false
        var receipt: S3RoundTripReceipt? = null
        runSuspendIO {
            try {
                withContext(Dispatchers.IO) {
                    client.createBucket { it.bucket(bucket) }
                    bucketCreated = true
                    client.putObject(
                        { it.bucket(bucket).key(key) },
                        RequestBody.fromString("service-connection-$ownerToken"),
                    )
                    objectCreated = true
                    val payload = client.getObjectAsBytes { it.bucket(bucket).key(key) }.asUtf8String()
                    payload shouldBeEqualTo "service-connection-$ownerToken"
                }
                receipt = S3RoundTripReceipt(
                    backend = emulator.javaClass.simpleName,
                    ownerToken = ownerToken,
                    bucket = bucket,
                    key = key,
                )
            } finally {
                withContext(Dispatchers.IO) {
                    if (objectCreated) {
                        requireOwnedResource(ownerToken, bucket, key)
                        runCatching { client.deleteObject { it.bucket(bucket).key(key) } }
                    }
                    if (bucketCreated) {
                        requireOwnedResource(ownerToken, bucket, key)
                        runCatching { client.deleteBucket { it.bucket(bucket) } }
                    }
                }
            }
        }
        return requireNotNull(receipt)
    }

    private fun requireOwnedResource(ownerToken: String, bucket: String, key: String) {
        require(ownerToken.isNotBlank()) { "owner token must not be blank" }
        require(bucket.contains(ownerToken)) { "foreign bucket rejected" }
        require(key.contains(ownerToken)) { "foreign object rejected" }
        require(!bucket.contains('*') && !key.contains('*')) { "wildcard resource rejected" }
    }
}

internal data class S3RoundTripReceipt(
    val backend: String,
    val ownerToken: String,
    val bucket: String,
    val key: String,
)
