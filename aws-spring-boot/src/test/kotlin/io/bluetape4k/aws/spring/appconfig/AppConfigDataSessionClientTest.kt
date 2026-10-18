package io.bluetape4k.aws.spring.appconfig

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.support.toUtf8String
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.appconfigdata.AppConfigDataClient
import software.amazon.awssdk.services.appconfigdata.model.GetLatestConfigurationRequest
import software.amazon.awssdk.services.appconfigdata.model.GetLatestConfigurationResponse
import software.amazon.awssdk.services.appconfigdata.model.StartConfigurationSessionRequest
import software.amazon.awssdk.services.appconfigdata.model.StartConfigurationSessionResponse

class AppConfigDataSessionClientTest {

    companion object: KLogging()

    @Test
    fun `cursor consumes the initial token and each response token exactly once`() {
        val client = FakeSessionClient()
        val cursor = AppConfigDataSessionCursor(
            client = client,
            request = AppConfigDataStartRequest(
                "app",
                "profile",
                "env",
                15
            ),
        )

        cursor.poll().nextPollConfigurationToken shouldBeEqualTo "token-2"
        cursor.poll().nextPollConfigurationToken shouldBeEqualTo "token-3"
        client.requestedTokens shouldBeEqualTo listOf("token-1", "token-2")
    }

    @Test
    fun `session response string does not expose token or body`() {
        val response = AppConfigDataResponse(
            "secret-token",
            15,
            "text/plain",
            "secret-body".toUtf8Bytes()
        )

        log.debug { "response=$response" }
        with(response.toString()) {
            this shouldNotContain "secret-token"
            this shouldNotContain "secret-body"
        }
    }

    @Test
    fun `initial load string does not expose configuration values`() {
        val initial = AppConfigDataInitialLoad(
            client = FakeSessionClient(),
            request = AppConfigDataStartRequest(
                "app",
                "profile",
                "env",
                15
            ),
            response = AppConfigDataResponse(
                "secret-token",
                15,
                "text/plain",
                "secret-body".toUtf8Bytes()
            ),
            values = mapOf("secret.key" to "secret-value"),
            refreshInterval = 30.seconds(),
            requiredMinimumPollInterval = 15.seconds(),
            format = AppConfigFormat.PROPERTIES,
            prefix = null,
        )

        log.debug { "initial=$initial" }
        with(initial.toString()) {
            this shouldNotContain "secret-token"
            this shouldNotContain "secret-body"
            this shouldNotContain "secret-value"
        }
    }

    @Test
    fun `SDK adapter maps identifiers required interval and next response token`() {
        val sdkClient = mockk<AppConfigDataClient>()
        val startRequest = slot<StartConfigurationSessionRequest>()
        val getRequest = slot<GetLatestConfigurationRequest>()

        every {
            sdkClient.startConfigurationSession(capture(startRequest))
        } returns
                StartConfigurationSessionResponse.builder()
                    .initialConfigurationToken("initial-token")
                    .build()
        every {
            sdkClient.getLatestConfiguration(capture(getRequest))
        } returns
                GetLatestConfigurationResponse.builder()
                    .nextPollConfigurationToken("next-token")
                    .nextPollIntervalInSeconds(30)
                    .contentType("text/plain")
                    .configuration(SdkBytes.fromUtf8String("feature.enabled=true"))
                    .build()

        val client = AppConfigDataSdkAdapter.sessionClient(sdkClient)
        val request = AppConfigDataStartRequest(
            "application",
            "profile",
            "environment",
            15
        )
        val response = client.startConfigurationSession(request)
            .let { client.getLatestConfiguration(it.initialConfigurationToken) }

        log.debug { "response=$response" }
        response.nextPollConfigurationToken shouldBeEqualTo "next-token"
        response.nextPollIntervalSeconds shouldBeEqualTo 30L
        response.configuration.toUtf8String() shouldBeEqualTo "feature.enabled=true"

        verify(exactly = 1) { sdkClient.startConfigurationSession(any<StartConfigurationSessionRequest>()) }
        verify(exactly = 1) { sdkClient.getLatestConfiguration(any<GetLatestConfigurationRequest>()) }

        log.debug { "startRequest=${startRequest.captured}" }

        with(startRequest.captured) {
            applicationIdentifier() shouldBeEqualTo "application"
            configurationProfileIdentifier() shouldBeEqualTo "profile"
            environmentIdentifier() shouldBeEqualTo "environment"
            requiredMinimumPollIntervalInSeconds() shouldBeEqualTo 15
        }
        getRequest.captured.configurationToken() shouldBeEqualTo "initial-token"
    }

    private class FakeSessionClient: AppConfigDataSessionClient {
        val requestedTokens = mutableListOf<String>()
        private var responseIndex = 0

        override fun startConfigurationSession(request: AppConfigDataStartRequest): AppConfigDataSession =
            AppConfigDataSession("token-1")

        override fun getLatestConfiguration(configurationToken: String): AppConfigDataResponse {
            requestedTokens += configurationToken
            responseIndex += 1
            return AppConfigDataResponse(
                nextPollConfigurationToken = "token-${responseIndex + 1}",
                nextPollIntervalSeconds = 15,
                contentType = "text/plain",
                configuration = byteArrayOf(),
            )
        }

        override fun close() = Unit
    }
}
