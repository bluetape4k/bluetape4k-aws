@file:Suppress("DEPRECATION")

package io.bluetape4k.aws.sfn

import io.bluetape4k.aws.AbstractAwsTest
import io.bluetape4k.testcontainers.aws.AwsEmulatorServer
import io.bluetape4k.testcontainers.aws.FlociServer
import io.bluetape4k.testcontainers.aws.LocalStackServer
import org.junit.jupiter.api.Assumptions.assumeFalse

/** Step Functions만 사용하는 Floci-first emulator fixture입니다. */
abstract class AbstractSfnTest : AbstractAwsTest() {

    companion object {
        private const val EMULATOR_PROPERTY = "bluetape4k.aws.emulator"

        val sfnEmulator: AwsEmulatorServer by lazy {
            when (configuredSfnEmulatorName()) {
                "floci" -> FlociServer.Launcher.floci
                "localstack" -> LocalStackServer.Launcher.getLocalStack("stepfunctions")
                else -> error("Unsupported AWS emulator: ${configuredSfnEmulatorName()}. Use floci or localstack.")
            }
        }

        fun assumeSfnSupported() {
            assumeFalse(
                configuredSfnEmulatorName() == "floci",
                "live integration unverified: Floci does not support Step Functions",
            )
        }

        private fun configuredSfnEmulatorName(): String =
            System.getProperty(EMULATOR_PROPERTY, "floci").trim().lowercase()
    }
}
