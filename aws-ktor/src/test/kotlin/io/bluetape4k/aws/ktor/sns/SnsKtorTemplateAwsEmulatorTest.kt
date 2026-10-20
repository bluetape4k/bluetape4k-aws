@file:Suppress("DEPRECATION")

package io.bluetape4k.aws.ktor.sns

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.sns.SnsClient
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldEndWith
import io.bluetape4k.assertions.shouldNotBeBlank
import io.bluetape4k.aws.kotlin.sns.withSnsClient
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.testcontainers.aws.AwsEmulatorServer
import io.bluetape4k.testcontainers.aws.FlociServer
import io.bluetape4k.testcontainers.aws.LocalStackServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnsKtorTemplateAwsEmulatorTest {

    companion object: KLoggingChannel()

    private val awsEmulator: AwsEmulatorServer by lazy { awsEmulator("sns") }

    @Test
    fun `create find and publish standard topic through AWS emulator SNS`() = runSuspendIO {
        withTestSnsClient { client ->
            val operations = SnsKtorTemplate(client)
            val topicName = "ktor-standard-${UUID.randomUUID()}"
            val topicArn = operations.createTopic(topicName)

            log.debug { "topicArn=$topicArn" }
            topicArn shouldEndWith ":$topicName"
            operations.findTopicArn(topicName) shouldBeEqualTo topicArn

            val published = operations.publish(
                SnsPublishRequest(
                    topicArn = topicArn,
                    subject = "standard",
                    message = "hello ktor sns",
                )
            )
            published.messageId.shouldNotBeBlank()
        }
    }

    @Test
    fun `create configured topic through AWS emulator SNS`() = runSuspendIO {
        withTestSnsClient { client ->
            val operations = SnsKtorTemplate(
                snsClient = client,
                topics = mapOf(
                    "ktor-configured" to SnsKtorTopic(attributes = mapOf("Environment" to "test"))
                ),
            )

            val topicArn = operations.createConfiguredTopic("ktor-configured")

            log.debug { "topicArn=$topicArn" }
            topicArn shouldEndWith ":ktor-configured"
            operations.findTopicArn("ktor-configured") shouldBeEqualTo topicArn
        }
    }

    private suspend inline fun <T> withTestSnsClient(crossinline block: suspend (SnsClient) -> T): T {
        val credentialsProvider = StaticCredentialsProvider {
            accessKeyId = awsEmulator.awsAccessKey
            secretAccessKey = awsEmulator.awsSecretKey
        }
        return withSnsClient(
            endpointUrl = Url.parse(awsEmulator.awsEndpoint.toString()),
            region = awsEmulator.regionName,
            credentialsProvider = credentialsProvider,
        ) { client -> block(client) }
    }

    private fun awsEmulator(vararg services: String): AwsEmulatorServer =
        when (val emulator = System.getProperty("bluetape4k.aws.emulator", "floci").trim().lowercase()) {
            "floci" -> FlociServer.Launcher.floci
            "localstack" -> LocalStackServer.Launcher.getLocalStack(*services)
            else -> error("Unsupported AWS emulator: $emulator. Use floci or localstack.")
        }
}
