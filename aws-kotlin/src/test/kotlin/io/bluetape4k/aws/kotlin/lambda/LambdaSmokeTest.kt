@file:Suppress("DEPRECATION")

package io.bluetape4k.aws.kotlin.lambda

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.testcontainers.aws.AwsEmulatorServer
import io.bluetape4k.testcontainers.aws.FlociServer
import io.bluetape4k.testcontainers.aws.LocalStackServer
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.opentest4j.TestAbortedException
import java.util.concurrent.TimeUnit

/** 사전 배포된 Lambda 함수만 호출하는 선택적 smoke 검증입니다. */
@Tag("lambda-smoke")
class LambdaSmokeTest {

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun `configured Lambda function can be invoked without a function error`() = runSuspendIO {
        assumeLambdaSupported()

        val functionName = requiredProperty(FUNCTION_NAME_PROPERTY)
        val region = requiredProperty(REGION_PROPERTY)
        val qualifier = System.getProperty(QUALIFIER_PROPERTY).orEmpty().takeIf(String::isNotBlank)
        val emulator = lambdaEmulator

        try {
            withLambdaClient(
                endpointUrl = Url.parse(emulator.awsEndpoint.toString()),
                region = region,
                credentialsProvider = StaticCredentialsProvider {
                    accessKeyId = emulator.awsAccessKey
                    secretAccessKey = emulator.awsSecretKey
                },
            ) { client ->
                val result = client.invokeString(functionName, "{}", qualifier = qualifier)
                check(!result.hasFunctionError) {
                    "configured Lambda invocation returned FunctionError"
                }
            }
        } catch (failure: Throwable) {
            if (failure.isLocalStackUnsupported()) {
                throw TestAbortedException(
                    "live integration unverified: LocalStack does not support Lambda invoke: " +
                        failure.javaClass.simpleName,
                    failure,
                )
            }
            throw failure
        }
    }

    private companion object {
        const val FUNCTION_NAME_PROPERTY = "bluetape4k.lambda.smoke.functionName"
        const val REGION_PROPERTY = "bluetape4k.lambda.smoke.region"
        const val EMULATOR_PROPERTY = "bluetape4k.lambda.smoke.emulator"
        const val QUALIFIER_PROPERTY = "bluetape4k.lambda.smoke.qualifier"

        val lambdaEmulator: AwsEmulatorServer by lazy {
            when (configuredEmulator()) {
                "floci" -> FlociServer.Launcher.floci
                "localstack" -> LocalStackServer.Launcher.getLocalStack("lambda")
                else -> error(
                    "Unsupported AWS emulator: ${configuredEmulator()}. Use floci or localstack.",
                )
            }
        }

        fun assumeLambdaSupported() {
            if (configuredEmulator() == "floci") {
                throw TestAbortedException("live integration unverified: Floci does not support Lambda invoke")
            }
        }

        fun configuredEmulator(): String =
            System.getProperty(EMULATOR_PROPERTY, "floci").trim().lowercase()

        fun requiredProperty(name: String): String =
            System.getProperty(name)?.trim()?.takeIf(String::isNotEmpty)
                ?: throw TestAbortedException("lambda-smoke: missing system property $name")

        fun Throwable.isLocalStackUnsupported(): Boolean =
            generateSequence(this) { it.cause }.any { throwable ->
                val text = "${throwable.javaClass.name}: ${throwable.message.orEmpty()}"
                text.contains("NotImplemented", ignoreCase = true) ||
                    Regex("\\b501\\b").containsMatchIn(text)
            }
    }
}
