@file:Suppress("DEPRECATION")

package io.bluetape4k.aws.kotlin

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.testcontainers.aws.AwsEmulatorServer
import io.bluetape4k.testcontainers.aws.FlociServer
import io.bluetape4k.testcontainers.aws.LocalStackServer
import org.junit.jupiter.api.Assumptions.assumeFalse

abstract class AbstractAwsTest {

    companion object: KLoggingChannel() {
        private const val EMULATOR_PROPERTY = "bluetape4k.aws.emulator"

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
        val awsEmulator: AwsEmulatorServer by lazy {
            when (val emulator = configuredAwsEmulatorName()) {
                "floci" -> FlociServer.Launcher.floci
                "localstack" -> LocalStackServer.Launcher.getLocalStack(*services.toTypedArray())
                else -> error("Unsupported AWS emulator: $emulator. Use floci or localstack.")
            }
        }

        @JvmStatic
        val localStackServer: AwsEmulatorServer by lazy { awsEmulator }

        val AwsEmulatorServer.endpointUrl: Url
            get() = Url.parse(this.awsEndpoint.toString())

        val AwsEmulatorServer.region: String
            get() = this.regionName

        val AwsEmulatorServer.credentialsProvider: StaticCredentialsProvider
            get() = StaticCredentialsProvider {
                accessKeyId = this@credentialsProvider.awsAccessKey
                secretAccessKey = this@credentialsProvider.awsSecretKey
            }

        @JvmStatic
        protected fun configuredAwsEmulatorName(): String {
            return System.getProperty(EMULATOR_PROPERTY, "floci").trim().lowercase()
        }

        @JvmStatic
        protected fun assumeFlociSupports(operation: String) {
            assumeFalse(
                configuredAwsEmulatorName() == "floci",
                "Floci does not currently support $operation; run with -D$EMULATOR_PROPERTY=localstack."
            )
        }

        @JvmStatic
        protected val faker = Fakers.faker

        @JvmStatic
        protected fun randomString(min: Int = 256, max: Int = 2048): String {
            return Fakers.randomString(min, max)
        }
    }
}
