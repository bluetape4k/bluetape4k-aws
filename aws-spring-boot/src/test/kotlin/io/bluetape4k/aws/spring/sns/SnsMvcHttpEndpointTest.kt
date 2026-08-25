package io.bluetape4k.aws.spring.sns

import io.bluetape4k.aws.spring.sns.annotation.endpoint.NotificationMessageMapping
import io.bluetape4k.aws.spring.sns.annotation.endpoint.NotificationSubscriptionMapping
import io.bluetape4k.aws.spring.sns.annotation.endpoint.NotificationUnsubscribeConfirmationMapping
import io.bluetape4k.aws.spring.sns.annotation.handlers.NotificationMessage
import io.bluetape4k.aws.spring.sns.annotation.handlers.NotificationMessageAttributes
import io.bluetape4k.aws.spring.sns.annotation.handlers.NotificationRawMessage
import io.bluetape4k.aws.spring.sns.annotation.handlers.NotificationSubject
import io.bluetape4k.aws.spring.sns.handlers.NotificationStatus
import io.bluetape4k.aws.spring.sqs.SnsMessageAttribute
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import software.amazon.awssdk.services.sns.model.ConfirmSubscriptionResponse
import tools.jackson.databind.ObjectMapper

class SnsMvcHttpEndpointTest {

    private val verifier = mockk<SnsHttpMessageVerifier>()
    private val operations = mockk<SnsOperations>(relaxed = true)
    private lateinit var controller: MvcController
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        clearMocks(verifier, operations)
        every { verifier.verify(any(), any(), any()) } answers {
            SnsHttpMessageParser.parse(firstArg(), secondArg())
        }
        coEvery { operations.confirmSubscription(any<SnsHttpMessage>(), any()) } returns
            ConfirmSubscriptionResponse.builder().subscriptionArn("sub-1").build()
        controller = MvcController(operations)
        val support = SnsHttpMessageResolverSupport(
            properties = SnsHttpEndpointProperties(expectedTopicArns = setOf(TOPIC_ARN)),
            verifierProvider = providerOf(verifier),
            objectMapper = ObjectMapper(),
            operations = operations,
        )
        val resolver = SnsMvcHttpMessageArgumentResolver(support)
        val filter = SnsHttpMessageServletFilter(support)
        val servletFilter: jakarta.servlet.Filter = filter
        val builder = MockMvcBuilders.standaloneSetup(controller)
        builder.setCustomArgumentResolvers(resolver)
        builder.addFilters<StandaloneMockMvcBuilder>(servletFilter)
        mockMvc = builder.build()
    }

    @Test
    fun `notification mapping resolves cached payload subject attributes and raw envelope`() {
        mockMvc.perform(
            post("/notifications")
                .header(SnsHttpMessageParser.MESSAGE_TYPE_HEADER, "Notification")
                .contentType(MediaType.TEXT_PLAIN)
                .content(notificationJson),
        ).andExpect(status().isNoContent)

        controller.payload shouldBeEqualTo OrderPayload("order-1")
        controller.subject shouldBeEqualTo "Order created"
        controller.attributes["contentType"] shouldBeEqualTo SnsMessageAttribute("String", "application/json")
        controller.raw?.topicArn shouldBeEqualTo TOPIC_ARN
        controller.invocations shouldBeEqualTo 1
    }

    @Test
    fun `invalid topic is rejected before handler`() {
        mockMvc.perform(
            post("/notifications")
                .header(SnsHttpMessageParser.MESSAGE_TYPE_HEADER, "Notification")
                .contentType(MediaType.TEXT_PLAIN)
                .content(notificationJson.replace(TOPIC_ARN, "arn:aws:sns:us-west-2:123456789012:Other")),
        ).andExpect(status().isForbidden)

        controller.invocations shouldBeEqualTo 0
        coVerify(exactly = 0) { operations.confirmSubscription(any<SnsHttpMessage>(), any()) }
    }

    @Test
    fun `invalid signature is rejected before MVC handler`() {
        every { verifier.verify(any(), any(), any()) } throws IllegalArgumentException("bad signature")

        mockMvc.perform(
            post("/notifications")
                .header(SnsHttpMessageParser.MESSAGE_TYPE_HEADER, "Notification")
                .contentType(MediaType.TEXT_PLAIN)
                .content(notificationJson),
        ).andExpect(status().isBadRequest)

        controller.invocations shouldBeEqualTo 0
        coVerify(exactly = 0) { operations.confirmSubscription(any<SnsHttpMessage>(), any()) }
    }

    @Test
    fun `missing notification subject resolves as null`() {
        mockMvc.perform(
            post("/notifications")
                .header(SnsHttpMessageParser.MESSAGE_TYPE_HEADER, "Notification")
                .contentType(MediaType.TEXT_PLAIN)
                .content(notificationJson.replace("\"Subject\" : \"Order created\",", "")),
        ).andExpect(status().isNoContent)

        controller.subject.shouldBeNull()
    }

    @Test
    fun `oversized body is rejected before parsing`() {
        val oversized = notificationJson + " ".repeat(SnsHttpMessageLimits.MAX_BYTES)
        mockMvc.perform(
            post("/notifications")
                .header(SnsHttpMessageParser.MESSAGE_TYPE_HEADER, "Notification")
                .contentType(MediaType.TEXT_PLAIN)
                .content(oversized),
        ).andExpect(status().isBadRequest)

        controller.invocations shouldBeEqualTo 0
    }

    @Test
    fun `header and envelope type mismatch is rejected before handler`() {
        mockMvc.perform(
            post("/notifications")
                .header(SnsHttpMessageParser.MESSAGE_TYPE_HEADER, "SubscriptionConfirmation")
                .contentType(MediaType.TEXT_PLAIN)
                .content(notificationJson),
        ).andExpect(status().isBadRequest)

        controller.invocations shouldBeEqualTo 0
    }

    @Test
    fun `malformed message attributes are rejected before handler`() {
        mockMvc.perform(
            post("/notifications")
                .header(SnsHttpMessageParser.MESSAGE_TYPE_HEADER, "Notification")
                .contentType(MediaType.TEXT_PLAIN)
                .content(notificationJson.replace(
                    "\"MessageAttributes\" : { " +
                        "\"contentType\": { \"Type\": \"String\", " +
                        "\"Value\": \"application/json\" } }",
                    "\"MessageAttributes\" : { \"contentType\": \"wrong-shape\" }",
                )),
        ).andExpect(status().isBadRequest)

        controller.invocations shouldBeEqualTo 0
    }

    @Test
    fun `secure defaults fail closed for empty allowlist and missing verifier`() {
        val emptyAllowlist = SnsHttpMessageResolverSupport(
            properties = SnsHttpEndpointProperties(),
            verifierProvider = providerOf(verifier),
        )
        val forbidden = assertFailsWith<ResponseStatusException> {
            emptyAllowlist.prepare(notificationJson, "Notification")
        }
        forbidden.statusCode.value() shouldBeEqualTo 403

        val missingVerifier = SnsHttpMessageResolverSupport(
            properties = SnsHttpEndpointProperties(expectedTopicArns = setOf(TOPIC_ARN)),
            verifierProvider = emptyVerifierProvider(),
        )
        val unavailable = assertFailsWith<ResponseStatusException> {
            missingVerifier.prepare(notificationJson, "Notification")
        }
        unavailable.statusCode.value() shouldBeEqualTo 503
    }

    @Test
    fun `subscription status is explicit and never auto confirms`() {
        mockMvc.perform(
            post("/subscriptions")
                .header(SnsHttpMessageParser.MESSAGE_TYPE_HEADER, "SubscriptionConfirmation")
                .contentType(MediaType.TEXT_PLAIN)
                .content(subscriptionJson),
        ).andExpect(status().isNoContent)

        controller.status?.topicArn shouldBeEqualTo TOPIC_ARN
        controller.status?.token shouldBeEqualTo "token-1"
        coVerify(exactly = 0) { operations.confirmSubscription(any<SnsHttpMessage>(), any()) }
        controller.status?.let { status ->
            runBlocking { status.confirmSubscription() }
        }
        coVerify(exactly = 1) { operations.confirmSubscription(any<SnsHttpMessage>(), true) }
    }

    @Test
    fun `unsubscribe status is routed and explicit`() {
        mockMvc.perform(
            post("/unsubscriptions")
                .header(SnsHttpMessageParser.MESSAGE_TYPE_HEADER, "UnsubscribeConfirmation")
                .contentType(MediaType.TEXT_PLAIN)
                .content(unsubscribeJson),
        ).andExpect(status().isNoContent)

        controller.status?.topicArn shouldBeEqualTo TOPIC_ARN
        controller.status?.token shouldBeEqualTo "token-1"
        coVerify(exactly = 0) { operations.confirmSubscription(any<SnsHttpMessage>(), any()) }
        controller.status?.let { status ->
            runBlocking { status.confirmSubscription(authenticateOnUnsubscribe = false) }
        }
        coVerify(exactly = 1) { operations.confirmSubscription(any<SnsHttpMessage>(), false) }
    }

    @Test
    fun `MVC suspend notification handler is invoked`() {
        mockMvc.perform(
            post("/notifications-suspend")
                .header(SnsHttpMessageParser.MESSAGE_TYPE_HEADER, "Notification")
                .contentType(MediaType.TEXT_PLAIN)
                .content(notificationJson),
        ).andExpect(status().isNoContent)

        controller.suspendInvocations shouldBeEqualTo 1
    }

    @RestController
    private class MvcController(private val operations: SnsOperations) {
        var payload: OrderPayload? = null
        var subject: String? = null
        var attributes: Map<String, SnsMessageAttribute> = emptyMap()
        var raw: SnsHttpMessage? = null
        var status: NotificationStatus? = null
        var invocations: Int = 0
        var suspendInvocations: Int = 0

        @NotificationMessageMapping(path = ["/notifications"])
        fun notification(
            @NotificationMessage payload: OrderPayload,
            @NotificationSubject subject: String?,
            @NotificationMessageAttributes attributes: Map<String, SnsMessageAttribute>,
            @NotificationRawMessage raw: SnsHttpMessage,
        ) {
            invocations++
            this.payload = payload
            this.subject = subject
            this.attributes = attributes
            this.raw = raw
        }

        @NotificationSubscriptionMapping(path = ["/subscriptions"])
        fun subscription(status: NotificationStatus) {
            invocations++
            this.status = status
        }

        @NotificationUnsubscribeConfirmationMapping(path = ["/unsubscriptions"])
        fun unsubscribe(status: NotificationStatus) {
            invocations++
            this.status = status
        }

        @NotificationMessageMapping(path = ["/notifications-suspend"])
        suspend fun suspendNotification(@NotificationMessage payload: OrderPayload) {
            suspendInvocations++
            this.payload = payload
        }
    }

    class OrderPayload() {
        var orderId: String = ""

        constructor(orderId: String) : this() {
            this.orderId = orderId
        }

        override fun equals(other: Any?): Boolean = other is OrderPayload && orderId == other.orderId
        override fun hashCode(): Int = orderId.hashCode()
    }

    private fun <T : Any> providerOf(value: T): ObjectProvider<T> = mockk {
        every { getIfAvailable() } returns value
    }

    private fun emptyVerifierProvider(): ObjectProvider<SnsHttpMessageVerifier> = mockk {
        every { getIfAvailable() } returns null
    }

    companion object {
        private const val TOPIC_ARN = "arn:aws:sns:us-west-2:123456789012:MyTopic"
        private val notificationJson =
            """
            {
              "Type" : "Notification",
              "MessageId" : "22b80b92-fdea-4c2c-8f9d-bdfb0c7bf324",
              "TopicArn" : "$TOPIC_ARN",
              "Subject" : "Order created",
              "Message" : "{\"orderId\":\"order-1\"}",
              "MessageAttributes" : { "contentType": { "Type": "String", "Value": "application/json" } },
              "Timestamp" : "2012-05-02T00:54:06.655Z",
              "SignatureVersion" : "2",
              "Signature" : "signature-2",
              "SigningCertURL" : "https://sns.us-west-2.amazonaws.com/SimpleNotificationService.pem",
              "UnsubscribeURL" : "https://sns.us-west-2.amazonaws.com/?Action=Unsubscribe&SubscriptionArn=sub-1"
            }
            """.trimIndent()

        private val subscriptionJson =
            """
            {
              "Type" : "SubscriptionConfirmation",
              "MessageId" : "22b80b92-fdea-4c2c-8f9d-bdfb0c7bf324",
              "TopicArn" : "$TOPIC_ARN",
              "Message" : "You have chosen to subscribe",
              "Timestamp" : "2012-05-02T00:54:06.655Z",
              "Token" : "token-1",
              "SubscribeURL" : "https://sns.us-west-2.amazonaws.com/?Action=ConfirmSubscription&TopicArn=$TOPIC_ARN&Token=token-1",
              "SignatureVersion" : "2",
              "Signature" : "signature-2",
              "SigningCertURL" : "https://sns.us-west-2.amazonaws.com/SimpleNotificationService.pem"
            }
            """.trimIndent()

        private val unsubscribeJson =
            subscriptionJson.replace("SubscriptionConfirmation", "UnsubscribeConfirmation")
    }
}
