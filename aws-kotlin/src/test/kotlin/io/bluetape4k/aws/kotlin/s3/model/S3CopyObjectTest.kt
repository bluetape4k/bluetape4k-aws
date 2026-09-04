package io.bluetape4k.aws.kotlin.s3.model

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.aws.kotlin.AbstractAwsTest
import io.bluetape4k.aws.kotlin.s3.copy
import io.bluetape4k.aws.kotlin.s3.ensureBucketExists
import io.bluetape4k.aws.kotlin.s3.forceDeleteBucket
import io.bluetape4k.aws.kotlin.s3.getAsString
import io.bluetape4k.aws.kotlin.s3.putFromString
import io.bluetape4k.aws.kotlin.s3.withS3Client
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

class S3CopyObjectTest {

    companion object : KLogging()

    @Test
    fun `copyObjectRequestOf는 원본과 대상 요청을 생성하고 source를 인코딩한다`() {
        val req = copyObjectRequestOf(
            srcBucket = "src-bucket",
            srcKey = "path/to/src.txt",
            destBucket = "dest-bucket",
            destKey = "path/to/dest.txt"
        )

        req.bucket shouldBeEqualTo "dest-bucket"
        req.key shouldBeEqualTo "path/to/dest.txt"
        req.copySource shouldBeEqualTo "src-bucket%2Fpath%2Fto%2Fsrc.txt"
    }

    @Test
    fun `copyObjectRequestOf copySource 문자열로 요청을 생성한다`() {
        val req = copyObjectRequestOf(
            copySource = "src-bucket%2Fpath%2Fto%2Fsrc%20file%2B.txt",
            destBucket = "dest-bucket",
            destKey = "path/to/dest.txt"
        )

        req.copySource shouldBeEqualTo "src-bucket%2Fpath%2Fto%2Fsrc%20file%2B.txt"
        req.bucket shouldBeEqualTo "dest-bucket"
        req.key shouldBeEqualTo "path/to/dest.txt"
    }

    @Test
    fun `copyObjectRequestOf는 빈 srcBucket을 허용하지 않는다`() {
        assertFailsWith<IllegalArgumentException> {
            copyObjectRequestOf(
                srcBucket = "",
                srcKey = "key",
                destBucket = "dest",
                destKey = "dest-key"
            )
        }
    }

    @Test
    fun `copyObjectRequestOf는 빈 destBucket을 허용하지 않는다`() {
        assertFailsWith<IllegalArgumentException> {
            copyObjectRequestOf(
                copySource = "src/key",
                destBucket = "",
                destKey = "dest-key"
            )
        }
    }

    @Test
    fun `copyObjectRequestOf는 빈 destKey를 허용하지 않는다`() {
        assertFailsWith<IllegalArgumentException> {
            copyObjectRequestOf(
                copySource = "src/key",
                destBucket = "dest-bucket",
                destKey = "  "
            )
        }
    }

    @Test
    fun `copyObjectRequestOf는 특수문자 원본을 S3 endpoint에서 복사한다`() = runSuspendIO {
        val emulator = AbstractAwsTest.awsEmulator
        val suffix = java.util.UUID.randomUUID().toString().replace("-", "")
        val sourceBucket = "issue-618-source-$suffix"
        val destinationBucket = "issue-618-destination-$suffix"
        val sourceKey = "folder/a b+c/한글?#.txt"
        val destinationKey = "copied.txt"
        val content = "copy-source"

        withS3Client(
            endpointUrl = Url.parse(emulator.awsEndpoint.toString()),
            region = emulator.regionName,
            credentialsProvider = StaticCredentialsProvider {
                accessKeyId = emulator.awsAccessKey
                secretAccessKey = emulator.awsSecretKey
            },
        ) { client ->
            withTestBuckets(
                buckets = listOf(sourceBucket, destinationBucket),
                create = { client.ensureBucketExists(it) },
                delete = { client.forceDeleteBucket(it) },
            ) {
                client.putFromString(sourceBucket, sourceKey, content)
                val response = client.copy(sourceBucket, sourceKey, destinationBucket, destinationKey)

                response.copyObjectResult.shouldNotBeNull()
                client.getAsString(destinationBucket, destinationKey).shouldNotBeNull() shouldBeEqualTo content
            }
        }
    }
}
