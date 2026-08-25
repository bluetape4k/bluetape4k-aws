package io.bluetape4k.aws.spring.sns

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.netty.buffer.ByteBufAllocator
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.NettyDataBufferFactory
import org.springframework.http.HttpStatus
import org.springframework.core.io.buffer.PooledDataBuffer
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import reactor.test.publisher.TestPublisher

class SnsHttpMessageWebFilterTest {

    private val support = SnsHttpMessageResolverSupport(
        properties = SnsHttpEndpointProperties(
            verificationRequired = false,
            allowStructuralOnly = true,
            expectedTopicArns = setOf(TOPIC_ARN),
        ),
    )

    @Test
    fun `releases joined buffer and replays decorated body for every subscription`() {
        val factory = NettyDataBufferFactory(ByteBufAllocator.DEFAULT)
        val source = factory.wrap(notificationJson.toByteArray())
        val publisher = TestPublisher.create<DataBuffer>()
        val request = MockServerHttpRequest.post("/")
            .header(SnsHttpMessageResolverSupport.SNS_MESSAGE_TYPE_HEADER, "Notification")
            .body(publisher.flux())
        val exchange = MockServerWebExchange.from(request)
        val bodies = mutableListOf<String>()
        val chain = WebFilterChain { downstream ->
            readBody(downstream).doOnNext(bodies::add)
                .then(readBody(downstream).doOnNext(bodies::add))
                .then()
        }

        StepVerifier.create(SnsHttpMessageWebFilter(support).filter(exchange, chain))
            .then { publisher.next(source); publisher.complete() }
            .verifyComplete()

        bodies shouldBeEqualTo listOf(notificationJson, notificationJson)
        (source as PooledDataBuffer).isAllocated.shouldBeFalse()
    }

    @Test
    fun `cancellation releases an in-flight pooled input buffer before handler`() {
        val factory = NettyDataBufferFactory(ByteBufAllocator.DEFAULT)
        val source = factory.wrap(notificationJson.toByteArray())
        val publisher = TestPublisher.create<DataBuffer>()
        val request = MockServerHttpRequest.post("/")
            .header(SnsHttpMessageResolverSupport.SNS_MESSAGE_TYPE_HEADER, "Notification")
            .body(publisher.flux())
        val exchange = MockServerWebExchange.from(request)
        var handlerInvocations = 0
        val chain = WebFilterChain {
            handlerInvocations++
            Mono.empty()
        }

        StepVerifier.create(SnsHttpMessageWebFilter(support).filter(exchange, chain))
            .then { publisher.next(source) }
            .thenCancel()
            .verify()

        handlerInvocations shouldBeEqualTo 0
        (source as PooledDataBuffer).isAllocated.shouldBeFalse()
    }

    @Test
    fun `chunked oversized body is rejected and releases every pooled chunk`() {
        val factory = NettyDataBufferFactory(ByteBufAllocator.DEFAULT)
        val first = factory.wrap(ByteArray(SnsHttpMessageLimits.MAX_BYTES))
        val second = factory.wrap(byteArrayOf(1))
        val publisher = TestPublisher.create<DataBuffer>()
        val request = MockServerHttpRequest.post("/")
            .header(SnsHttpMessageResolverSupport.SNS_MESSAGE_TYPE_HEADER, "Notification")
            .body(publisher.flux())
        val exchange = MockServerWebExchange.from(request)
        val chain = WebFilterChain { Mono.empty() }

        StepVerifier.create(SnsHttpMessageWebFilter(support).filter(exchange, chain))
            .then {
                publisher.next(first)
                publisher.next(second)
                publisher.complete()
            }
            .verifyComplete()

        exchange.response.statusCode shouldBeEqualTo HttpStatus.BAD_REQUEST
        (first as PooledDataBuffer).isAllocated.shouldBeFalse()
        (second as PooledDataBuffer).isAllocated.shouldBeFalse()
    }

    private fun readBody(exchange: ServerWebExchange): Mono<String> =
        DataBufferUtils.join(exchange.request.body)
            .map { buffer ->
                try {
                    ByteArray(buffer.readableByteCount()).also(buffer::read).toString(Charsets.UTF_8)
                } finally {
                    DataBufferUtils.release(buffer)
                }
            }

    companion object {
        private const val TOPIC_ARN = "arn:aws:sns:us-west-2:123456789012:MyTopic"
        private val notificationJson =
            """
            {
              "Type" : "Notification",
              "MessageId" : "22b80b92-fdea-4c2c-8f9d-bdfb0c7bf324",
              "TopicArn" : "$TOPIC_ARN",
              "Message" : "hello",
              "Timestamp" : "2012-05-02T00:54:06.655Z",
              "SignatureVersion" : "2",
              "Signature" : "signature-2",
              "SigningCertURL" : "https://sns.us-west-2.amazonaws.com/SimpleNotificationService.pem"
            }
            """.trimIndent()
    }
}
