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
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.MethodParameter
import org.springframework.web.reactive.BindingContext
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import software.amazon.awssdk.services.sns.model.ConfirmSubscriptionResponse
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SnsWebFluxHttpEndpointTest {

    private val verifier = mockk<SnsHttpMessageVerifier>()
    private val operations = mockk<SnsOperations>(relaxed = true)
    private lateinit var controller: WebFluxController
    private lateinit var client: WebTestClient
    private lateinit var resolver: CountingArgumentResolver

    @BeforeEach
    fun setUp() {
        clearMocks(verifier, operations)
        every { verifier.verify(any(), any(), any()) } answers {
            SnsHttpMessageParser.parse(firstArg(), secondArg())
        }
        coEvery { operations.confirmSubscription(any<SnsHttpMessage>(), any()) } returns
            ConfirmSubscriptionResponse.builder().subscriptionArn("sub-1").build()
        controller = WebFluxController(operations)
        val support = SnsHttpMessageResolverSupport(
            properties = SnsHttpEndpointProperties(expectedTopicArns = setOf(TOPIC_ARN)),
            verifierProvider = providerOf(verifier),
            objectMapper = ObjectMapper(),
            operations = operations,
        )
        resolver = CountingArgumentResolver(SnsWebFluxHttpMessageArgumentResolver(support))
        val filter = SnsHttpMessageWebFilter(support)
        val spec = WebTestClient.bindToController(controller)
            .argumentResolvers { configurer -> configurer.addCustomResolver(resolver) }
        spec.webFilter< WebTestClient.ControllerSpec >(filter)
        client = spec.configureClient().build()
    }

    @Test
    fun `notification mapping resolves replayed body in suspend handler`() {
        client.post().uri("/notifications")
            .header(SnsHttpMessageParser.MESSAGE_TYPE_HEADER, "Notification")
            .contentType(MediaType.TEXT_PLAIN)
            .bodyValue(notificationJson)
            .exchange()
            .expectStatus().isNoContent

        controller.payload shouldBeEqualTo WebFluxController.OrderPayload("order-1")
        controller.subject shouldBeEqualTo "Order created"
        controller.attributes["contentType"] shouldBeEqualTo SnsMessageAttribute("String", "application/json")
        controller.raw?.topicArn shouldBeEqualTo TOPIC_ARN
        controller.invocations shouldBeEqualTo 1
    }

    @Test
    fun `standalone resolver caches one prepared message for multiple parameters`() {
        val standaloneClient = WebTestClient.bindToController(controller)
            .argumentResolvers { configurer ->
                configurer.addCustomResolver(
                    SnsWebFluxHttpMessageArgumentResolver(
                        SnsHttpMessageResolverSupport(
                            properties = SnsHttpEndpointProperties(
                                expectedTopicArns = setOf(TOPIC_ARN),
                            ),
                            verifierProvider = providerOf(verifier),
                            objectMapper = ObjectMapper(),
                            operations = operations,
                        ),
                    ),
                )
            }
            .configureClient()
            .build()

        standaloneClient.post().uri("/standalone")
            .header(SnsHttpMessageParser.MESSAGE_TYPE_HEADER, "Notification")
            .contentType(MediaType.TEXT_PLAIN)
            .bodyValue(notificationJson)
            .exchange()
            .expectStatus().isNoContent

        verify(exactly = 1) { verifier.verify(any(), any(), any()) }
        controller.invocations shouldBeEqualTo 1
    }

    @Test
    fun `invalid signature or allowlist prevents reactive handler`() {
        every { verifier.verify(any(), any(), any()) } throws IllegalArgumentException("bad signature")

        client.post().uri("/notifications")
            .header(SnsHttpMessageParser.MESSAGE_TYPE_HEADER, "Notification")
            .contentType(MediaType.TEXT_PLAIN)
            .bodyValue(notificationJson)
            .exchange()
            .expectStatus().isBadRequest

        controller.invocations shouldBeEqualTo 0
        coVerify(exactly = 0) { operations.confirmSubscription(any<SnsHttpMessage>(), any()) }
    }

    @Test
    fun `resolverless mapping still passes through the pre-handler security gate`() {
        client.post().uri("/resolverless")
            .header(SnsHttpMessageParser.MESSAGE_TYPE_HEADER, "Notification")
            .contentType(MediaType.TEXT_PLAIN)
            .bodyValue(notificationJson)
            .exchange()
            .expectStatus().isNoContent

        controller.invocations shouldBeEqualTo 1
        verify(exactly = 1) { verifier.verify(any(), any(), any()) }

        clearMocks(verifier)
        every { verifier.verify(any(), any(), any()) } throws IllegalArgumentException("bad signature")
        client.post().uri("/resolverless")
            .header(SnsHttpMessageParser.MESSAGE_TYPE_HEADER, "Notification")
            .contentType(MediaType.TEXT_PLAIN)
            .bodyValue(notificationJson)
            .exchange()
            .expectStatus().isBadRequest

        controller.invocations shouldBeEqualTo 1

        val wrongTopic = notificationJson.replace(TOPIC_ARN, "arn:aws:sns:us-west-2:123456789012:Other")
        client.post().uri("/resolverless")
            .header(SnsHttpMessageParser.MESSAGE_TYPE_HEADER, "Notification")
            .contentType(MediaType.TEXT_PLAIN)
            .bodyValue(wrongTopic)
            .exchange()
            .expectStatus().isForbidden

        val missingVerifierSupport = SnsHttpMessageResolverSupport(
            properties = SnsHttpEndpointProperties(expectedTopicArns = setOf(TOPIC_ARN)),
            verifierProvider = emptyVerifierProvider(),
            objectMapper = ObjectMapper(),
            operations = operations,
        )
        val missingVerifierClient = WebTestClient.bindToController(controller)
            .argumentResolvers { configurer ->
                configurer.addCustomResolver(SnsWebFluxHttpMessageArgumentResolver(missingVerifierSupport))
            }
            .webFilter< WebTestClient.ControllerSpec >(SnsHttpMessageWebFilter(missingVerifierSupport))
            .configureClient()
            .build()
        missingVerifierClient.post().uri("/resolverless")
            .header(SnsHttpMessageParser.MESSAGE_TYPE_HEADER, "Notification")
            .contentType(MediaType.TEXT_PLAIN)
            .bodyValue(notificationJson)
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)

        controller.invocations shouldBeEqualTo 1
    }

    @Test
    fun `chunked oversized body is rejected before reactive handler`() {
        client.post().uri("/notifications")
            .header(SnsHttpMessageParser.MESSAGE_TYPE_HEADER, "Notification")
            .contentType(MediaType.TEXT_PLAIN)
            .bodyValue(notificationJson + " ".repeat(SnsHttpMessageLimits.MAX_BYTES))
            .exchange()
            .expectStatus().isBadRequest

        controller.invocations shouldBeEqualTo 0
    }

    @Test
    fun `bounded concurrent notifications keep body and envelope isolated`() {
        val verifierBarrier = CyclicBarrier(VERIFIER_OVERLAP_PARTICIPANTS)
        val verifierParticipants = AtomicInteger()
        val verifierInFlight = AtomicInteger()
        val maxVerifierInFlight = AtomicInteger()
        every { verifier.verify(any(), any(), any()) } answers {
            val active = verifierInFlight.incrementAndGet()
            maxVerifierInFlight.updateAndGet { current -> maxOf(current, active) }
            try {
                if (verifierParticipants.getAndIncrement() < VERIFIER_OVERLAP_PARTICIPANTS) {
                    verifierBarrier.await(VERIFIER_BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
                SnsHttpMessageParser.parse(firstArg(), secondArg())
            } finally {
                verifierInFlight.decrementAndGet()
            }
        }
        val requestIndex = AtomicInteger()
        val totalRequests = CONCURRENCY * ROUNDS

        MultithreadingTester()
            .workers(CONCURRENCY)
            .rounds(ROUNDS)
            .add {
                val index = requestIndex.getAndIncrement()
                val orderId = "order-$index"
                val topicArn = if (index % 3 == 0) OTHER_TOPIC_ARN else TOPIC_ARN
                val expectedStatus = if (topicArn == TOPIC_ARN) HttpStatus.NO_CONTENT else HttpStatus.FORBIDDEN

                client.post().uri("/notifications")
                    .header(SnsHttpMessageParser.MESSAGE_TYPE_HEADER, "Notification")
                    .contentType(MediaType.TEXT_PLAIN)
                    .bodyValue(notificationJsonFor(orderId, "message-$index", topicArn))
                    .exchange()
                    .expectStatus().isEqualTo(expectedStatus)
            }
            .run()

        val expectedAcceptedOrderIds = (0 until totalRequests)
            .filterNot { it % 3 == 0 }
            .map { "order-$it" }
            .toSet()
        val expectedAcceptedMessageIds = expectedAcceptedOrderIds
            .map { it.replace("order-", "message-") }
            .toSet()
        val observed = controller.observations.toList()

        requestIndex.get() shouldBeEqualTo totalRequests
        observed.size shouldBeEqualTo expectedAcceptedOrderIds.size
        observed.map { it.orderId }.toSet() shouldBeEqualTo expectedAcceptedOrderIds
        observed.map { it.messageId }.toSet() shouldBeEqualTo expectedAcceptedMessageIds
        observed.map { it.orderId to it.messageId }.toSet() shouldBeEqualTo
            expectedAcceptedOrderIds.map { it to it.replace("order-", "message-") }.toSet()
        controller.invocations shouldBeEqualTo expectedAcceptedOrderIds.size
        resolver.invocations shouldBeEqualTo expectedAcceptedOrderIds.size * NOTIFICATION_PARAMETER_COUNT
        verify(exactly = expectedAcceptedOrderIds.size) { verifier.verify(any(), any(), any()) }
        coVerify(exactly = 0) { operations.confirmSubscription(any<SnsHttpMessage>(), any()) }
        maxVerifierInFlight.get() shouldBeGreaterThan 1
    }

    @Test
    fun `subscription confirmation is explicit`() {
        client.post().uri("/subscriptions")
            .header(SnsHttpMessageParser.MESSAGE_TYPE_HEADER, "SubscriptionConfirmation")
            .contentType(MediaType.TEXT_PLAIN)
            .bodyValue(subscriptionJson)
            .exchange()
            .expectStatus().isNoContent

        coVerify(exactly = 0) { operations.confirmSubscription(any<SnsHttpMessage>(), any()) }
        runBlocking { controller.status?.confirmSubscription() }
        coVerify(exactly = 1) { operations.confirmSubscription(any<SnsHttpMessage>(), true) }
    }

    @Test
    fun `unsubscribe confirmation is explicit`() {
        client.post().uri("/unsubscriptions")
            .header(SnsHttpMessageParser.MESSAGE_TYPE_HEADER, "UnsubscribeConfirmation")
            .contentType(MediaType.TEXT_PLAIN)
            .bodyValue(unsubscribeJson)
            .exchange()
            .expectStatus().isNoContent

        coVerify(exactly = 0) { operations.confirmSubscription(any<SnsHttpMessage>(), any()) }
        runBlocking { controller.status?.confirmSubscription(authenticateOnUnsubscribe = false) }
        coVerify(exactly = 1) { operations.confirmSubscription(any<SnsHttpMessage>(), false) }
    }

    @RestController
    private class WebFluxController(private val operations: SnsOperations) {
        var payload: OrderPayload? = null
        var subject: String? = null
        var attributes: Map<String, SnsMessageAttribute> = emptyMap()
        var raw: SnsHttpMessage? = null
        var status: NotificationStatus? = null
        private val invocationCounter = AtomicInteger()
        val invocations: Int get() = invocationCounter.get()
        val observations = ConcurrentLinkedQueue<NotificationObservation>()

        @NotificationMessageMapping(path = ["/notifications"])
        suspend fun notification(
            @NotificationMessage payload: OrderPayload,
            @NotificationSubject subject: String?,
            @NotificationMessageAttributes attributes: Map<String, SnsMessageAttribute>,
            @NotificationRawMessage raw: SnsHttpMessage,
        ) {
            invocationCounter.incrementAndGet()
            this.payload = payload
            this.subject = subject
            this.attributes = attributes
            this.raw = raw
            observations += NotificationObservation(payload.orderId, raw.messageId)
        }

        @NotificationSubscriptionMapping(path = ["/subscriptions"])
        fun subscription(status: NotificationStatus) {
            invocationCounter.incrementAndGet()
            this.status = status
        }

        @NotificationUnsubscribeConfirmationMapping(path = ["/unsubscriptions"])
        fun unsubscribe(status: NotificationStatus) {
            invocationCounter.incrementAndGet()
            this.status = status
        }

        @NotificationMessageMapping(path = ["/resolverless"])
        fun resolverless() {
            invocationCounter.incrementAndGet()
        }

        @NotificationMessageMapping(path = ["/standalone"])
        fun standalone(
            @NotificationRawMessage raw: SnsHttpMessage,
            @NotificationSubject subject: String?,
        ) {
            invocationCounter.incrementAndGet()
            this.raw = raw
            this.subject = subject
        }

        data class NotificationObservation(
            val orderId: String,
            val messageId: String,
        )

        class OrderPayload() {
            var orderId: String = ""

            constructor(orderId: String) : this() {
                this.orderId = orderId
            }

            override fun equals(other: Any?): Boolean = other is OrderPayload && orderId == other.orderId
            override fun hashCode(): Int = orderId.hashCode()
        }
    }

    private class CountingArgumentResolver(
        private val delegate: HandlerMethodArgumentResolver,
    ) : HandlerMethodArgumentResolver {
        private val invocationCounter = AtomicInteger()
        val invocations: Int get() = invocationCounter.get()

        override fun supportsParameter(parameter: MethodParameter): Boolean =
            delegate.supportsParameter(parameter)

        override fun resolveArgument(
            parameter: MethodParameter,
            bindingContext: BindingContext,
            exchange: ServerWebExchange,
        ): Mono<Any> {
            invocationCounter.incrementAndGet()
            return delegate.resolveArgument(parameter, bindingContext, exchange)
        }
    }

    private fun <T : Any> providerOf(value: T): ObjectProvider<T> = mockk {
        every { getIfAvailable() } returns value
    }

    private fun emptyVerifierProvider(): ObjectProvider<SnsHttpMessageVerifier> = mockk {
        every { getIfAvailable() } returns null
    }

    companion object {
        private const val TOPIC_ARN = "arn:aws:sns:us-west-2:123456789012:MyTopic"
        private const val OTHER_TOPIC_ARN = "arn:aws:sns:us-west-2:123456789012:OtherTopic"
        private const val CONCURRENCY = 4
        private const val ROUNDS = 2
        private const val NOTIFICATION_PARAMETER_COUNT = 4
        private const val VERIFIER_OVERLAP_PARTICIPANTS = 2
        private const val VERIFIER_BARRIER_TIMEOUT_SECONDS = 5L
        private val notificationJson = notificationJsonFor(
            orderId = "order-1",
            messageId = "22b80b92-fdea-4c2c-8f9d-bdfb0c7bf324",
        )

        private fun notificationJsonFor(
            orderId: String,
            messageId: String,
            topicArn: String = TOPIC_ARN,
        ) =
            """
            {
              "Type" : "Notification",
              "MessageId" : "$messageId",
              "TopicArn" : "$topicArn",
              "Subject" : "Order created",
              "Message" : "{\"orderId\":\"$orderId\"}",
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
