@file:Suppress("DEPRECATION")

package io.bluetape4k.aws

import io.bluetape4k.aws.auth.staticCredentialsProviderOf
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.aws.AwsEmulatorServer
import io.bluetape4k.testcontainers.aws.FlociServer
import io.bluetape4k.testcontainers.aws.LocalStackServer
import org.junit.jupiter.api.Assumptions.assumeFalse
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import java.net.URI

abstract class AbstractAwsTest {

    companion object: KLogging() {
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

        val AwsEmulatorServer.endpoint: URI get() = awsEndpoint

        fun AwsEmulatorServer.region(): Region = Region.of(this.regionName)

        val AwsEmulatorServer.credentialsProvider: StaticCredentialsProvider
            get() = staticCredentialsProviderOf(this.awsAccessKey, this.awsSecretKey)

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
        protected fun randomString(): String {
            return Fakers.randomString(256, 2048)
        }
    }
}
