package io.bluetape4k.aws.examples.ktor.s3

import io.bluetape4k.aws.ktor.s3.S3KtorAddressingStyle
import io.bluetape4k.aws.ktor.s3.S3KtorClient
import io.bluetape4k.aws.ktor.s3.S3KtorPresignedRequest
import io.bluetape4k.aws.ktor.s3.s3KtorClientOf
import io.ktor.http.Url
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import java.time.Clock
import java.time.Duration

object S3KtorExamples {

    fun localStackClient(signingClock: Clock? = null): S3KtorClient =
        s3KtorClientOf(
            region = "ap-northeast-2",
            credentialsProvider = StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test")
            ),
            endpointOverride = Url("http://localhost:4566"),
            addressingStyle = S3KtorAddressingStyle.Path,
            signingClock = signingClock,
        )

    suspend fun uploadAndDownloadText(
        s3: S3KtorClient,
        bucket: String,
        key: String,
        text: String,
    ): String {
        s3.putObject(
            bucket = bucket,
            key = key,
            bytes = text.encodeToByteArray(),
            contentType = "text/plain; charset=utf-8",
            metadata = mapOf("example" to "aws-ktor-s3"),
        )

        return s3.getObjectBytes(bucket, key).decodeToString()
    }

    fun presignDownload(
        s3: S3KtorClient,
        bucket: String,
        key: String,
        expires: Duration = Duration.ofMinutes(15),
    ): S3KtorPresignedRequest =
        s3.presignGetObject(bucket, key, expires)
}
