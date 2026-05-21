@file:Suppress("DEPRECATION")

package io.bluetape4k.aws.spring.test

import io.bluetape4k.testcontainers.aws.AwsEmulatorServer
import io.bluetape4k.testcontainers.aws.FlociServer
import io.bluetape4k.testcontainers.aws.LocalStackServer
import io.bluetape4k.testcontainers.aws.MiniStackServer

internal object AwsSpringBootTestEmulator {

    private const val EMULATOR_PROPERTY = "bluetape4k.aws.emulator"

    fun get(vararg services: String): AwsEmulatorServer {
        return when (val emulator = System.getProperty(EMULATOR_PROPERTY, "floci").trim().lowercase()) {
            "floci" -> FlociServer.Launcher.floci
            "localstack" -> LocalStackServer.Launcher.getLocalStack(*services)
            "ministack" -> MiniStackServer.Launcher.miniStack
            else -> error("Unsupported AWS emulator: $emulator. Use floci, localstack, or ministack.")
        }
    }
}
