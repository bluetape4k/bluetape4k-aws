@file:Suppress("DEPRECATION")

package io.bluetape4k.aws.ktor.sns

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldEndWith
import io.bluetape4k.assertions.shouldNotBeBlank
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.testcontainers.aws.AwsEmulatorServer
import io.bluetape4k.testcontainers.aws.FlociServer
import io.bluetape4k.testcontainers.aws.LocalStackServer
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sns.SnsAsyncClient
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnsKtorTemplateAwsEmulatorTest {

    private val awsEmulator: AwsEmulatorServer by lazy { awsEmulator("sns") }

    @Test
    fun `create find and publish standard topic through AWS emulator SNS`() = runSuspendIO {
        snsClient().use { client ->
            val operations = SnsKtorTemplate(client)
            val topicName = "ktor-standard-${UUID.randomUUID()}"
            val topicArn = operations.createTopic(topicName)

            topicArn shouldEndWith ":$topicName"
            operations.findTopicArn(topicName) shouldBeEqualTo topicArn

            val published = operations.publish(
                SnsPublishRequest(
                    topicArn = topicArn,
                    subject = "standard",
                    message = "hello ktor sns",
                )
            )
            published.messageId().shouldNotBeBlank()
        }
    }

    @Test
    fun `create configured topic through AWS emulator SNS`() = runSuspendIO {
        snsClient().use { client ->
            val operations = SnsKtorTemplate(
                snsAsyncClient = client,
                topics = mapOf(
                    "ktor-configured" to SnsKtorTopic(
                        attributes = mapOf("Environment" to "test"),
                    )
                ),
            )

            val topicArn = operations.createConfiguredTopic("ktor-configured")

            topicArn shouldEndWith ":ktor-configured"
            operations.findTopicArn("ktor-configured") shouldBeEqualTo topicArn
        }
    }

    private fun snsClient(): SnsAsyncClient =
        SnsAsyncClient.builder()
            .endpointOverride(awsEmulator.awsEndpoint)
            .region(Region.of(awsEmulator.regionName))
            .credentialsProvider(awsEmulator.getCredentialProvider())
            .build()

    private fun awsEmulator(vararg services: String): AwsEmulatorServer =
        when (val emulator = System.getProperty("bluetape4k.aws.emulator", "floci").trim().lowercase()) {
            "floci" -> FlociServer.Launcher.floci
            "localstack" -> LocalStackServer.Launcher.getLocalStack(*services)
            else -> error("Unsupported AWS emulator: $emulator. Use floci or localstack.")
        }
}
