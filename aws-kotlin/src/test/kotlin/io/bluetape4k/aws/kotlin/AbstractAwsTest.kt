package io.bluetape4k.aws.kotlin

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.testcontainers.aws.LocalStackServer

abstract class AbstractAwsTest {

    companion object: KLoggingChannel() {

        val services = listOf(
            "cloudwatch",
            "logs",
            "dynamodb",
            "kinesis",
            "kms",
            "s3",
            "ses",
            "sns",
            "sqs",
            "sts"
        )

        @JvmStatic
        val awsEmulator: LocalStackServer by lazy {
            LocalStackServer.Launcher.getLocalStack(*services.toTypedArray())
        }

        @JvmStatic
        val localStackServer: LocalStackServer by lazy { awsEmulator }

        val LocalStackServer.endpointUrl: Url
            get() = Url.parse(this.endpoint.toString())

        val LocalStackServer.credentialsProvider: StaticCredentialsProvider
            get() = StaticCredentialsProvider {
                accessKeyId = this@credentialsProvider.accessKey
                secretAccessKey = this@credentialsProvider.secretKey
            }

        @JvmStatic
        protected val faker = Fakers.faker

        @JvmStatic
        protected fun randomString(min: Int = 256, max: Int = 2048): String {
            return Fakers.randomString(min, max)
        }
    }
}
