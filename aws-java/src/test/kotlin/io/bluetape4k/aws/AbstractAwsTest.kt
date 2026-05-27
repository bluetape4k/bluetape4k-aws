package io.bluetape4k.aws

import io.bluetape4k.aws.auth.staticCredentialsProviderOf
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.aws.LocalStackServer
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region

abstract class AbstractAwsTest {

    companion object: KLogging() {
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

        fun LocalStackServer.region(): Region = Region.of(this.region)

        val LocalStackServer.credentialsProvider: StaticCredentialsProvider
            get() = staticCredentialsProviderOf(this.accessKey, this.secretKey)

        @JvmStatic
        protected val faker = Fakers.faker

        @JvmStatic
        protected fun randomString(): String {
            return Fakers.randomString(256, 2048)
        }
    }
}
