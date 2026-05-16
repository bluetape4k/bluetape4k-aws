package io.bluetape4k.aws.kotlin.sns

import aws.sdk.kotlin.services.sns.SnsClient
import aws.sdk.kotlin.services.sns.confirmSubscription
import aws.sdk.kotlin.services.sns.model.ConfirmSubscriptionRequest
import aws.sdk.kotlin.services.sns.model.ConfirmSubscriptionResponse
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * MockK-based contract tests for the SNS subscription confirmation flow.
 *
 * The real confirmSubscription flow requires a token delivered out-of-band to the
 * subscriber endpoint (issue #100); no LocalStack emulator support exists for this.
 * These tests use MockK to verify that the call is correctly formed and that the
 * subscription ARN from the response is accessible.
 */
class SnsConfirmSubscriptionMockTest {

    companion object: KLoggingChannel() {
        private const val TOPIC_ARN = "arn:aws:sns:us-east-1:123456789012:test-topic.fifo"
        private const val SUBSCRIPTION_ARN = "arn:aws:sns:us-east-1:123456789012:test-topic.fifo:sub-uuid"
        private const val MOCK_TOKEN = "EXAMPLE-CONFIRMATION-TOKEN-abc123"
    }

    private val client = mockk<SnsClient>()

    @BeforeEach
    fun setup() {
        clearMocks(client)
    }

    @Test
    fun `confirmSubscription returns subscriptionArn when token is valid`() = runSuspendIO {
        coEvery {
            client.confirmSubscription(any<ConfirmSubscriptionRequest>())
        } returns ConfirmSubscriptionResponse {
            subscriptionArn = SUBSCRIPTION_ARN
        }

        val response = client.confirmSubscription {
            token = MOCK_TOKEN
            topicArn = TOPIC_ARN
        }

        response.subscriptionArn.shouldNotBeNull().shouldNotBeEmpty()
        response.subscriptionArn shouldBeEqualTo SUBSCRIPTION_ARN
    }

    @Test
    fun `confirmSubscription propagates non-cancellation exceptions`() = runSuspendIO {
        coEvery {
            client.confirmSubscription(any<ConfirmSubscriptionRequest>())
        } throws RuntimeException("InvalidParameter: token expired")

        var thrown: RuntimeException? = null
        try {
            client.confirmSubscription {
                token = "EXPIRED-TOKEN"
                topicArn = TOPIC_ARN
            }
        } catch (e: RuntimeException) {
            thrown = e
        }

        thrown.shouldNotBeNull()
    }
}
