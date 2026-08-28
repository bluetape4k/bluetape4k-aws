package io.bluetape4k.aws.spring.sns

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.netty.buffer.ByteBufAllocator
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.NettyDataBufferFactory
import org.springframework.core.io.buffer.PooledDataBuffer
import org.springframework.http.HttpStatus
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.http.server.reactive.MockServerHttpResponse
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.adapter.DefaultServerWebExchange
import org.springframework.web.server.i18n.AcceptHeaderLocaleContextResolver
import org.springframework.web.server.session.DefaultWebSessionManager
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.Disposable
import reactor.core.Disposables
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.publisher.SignalType
import reactor.core.scheduler.Scheduler
import reactor.test.StepVerifier
import reactor.test.publisher.TestPublisher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SnsHttpMessageWebFilterTest {

    private val operations = mockk<SnsOperations>(relaxed = true)
    private val support = SnsHttpMessageResolverSupport(
        properties = SnsHttpEndpointProperties(
            verificationRequired = false,
            allowStructuralOnly = true,
            expectedTopicArns = setOf(TOPIC_ARN),
        ),
        operations = operations,
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
    fun `downstream cancellation after replay releases pooled buffers before handler`() {
        val factory = NettyDataBufferFactory(ByteBufAllocator.DEFAULT)
        val source = factory.wrap(notificationJson.toByteArray())
        val publisher = TestPublisher.create<DataBuffer>()
        val request = MockServerHttpRequest.post("/")
            .header(SnsHttpMessageResolverSupport.SNS_MESSAGE_TYPE_HEADER, "Notification")
            .body(publisher.flux())
        val exchange = DefaultServerWebExchange(
            request,
            MockServerHttpResponse(factory),
            DefaultWebSessionManager(),
            ServerCodecConfigurer.create(),
            AcceptHeaderLocaleContextResolver(),
        )
        val probe = DownstreamCancellationProbe()

        StepVerifier.create(SnsHttpMessageWebFilter(support).filter(exchange, probe.chain))
            .then { publisher.next(source); publisher.complete() }
            .then { probe.awaitReplay(BARRIER_TIMEOUT_SECONDS) }
            .thenCancel()
            .verify()
        probe.awaitCancellation(BARRIER_TIMEOUT_SECONDS)

        probe.chainSubscriptions.get() shouldBeEqualTo 1
        probe.bodySubscriptions.get() shouldBeEqualTo 1
        probe.activeBodySubscriptions.get() shouldBeEqualTo 0
        probe.handlerInvocations.get() shouldBeEqualTo 0
        probe.downstreamSignal.get() shouldBeEqualTo SignalType.CANCEL
        probe.replayBodies shouldBeEqualTo listOf(notificationJson)
        (source as PooledDataBuffer).isAllocated.shouldBeFalse()
        probe.replayBuffers.forEach { (it as PooledDataBuffer).isAllocated.shouldBeFalse() }
        exchange.response.statusCode shouldBeEqualTo null
        coVerify(exactly = 0) {
            operations.confirmSubscription(any<SnsHttpMessage>(), any())
        }
    }

    @Test
    fun `downstream cancellation discards an in-flight replay pooled buffer`() {
        val factory = NettyDataBufferFactory(ByteBufAllocator.DEFAULT)
        val source = factory.wrap(notificationJson.toByteArray())
        val publisher = TestPublisher.create<DataBuffer>()
        val request = MockServerHttpRequest.post("/")
            .header(SnsHttpMessageResolverSupport.SNS_MESSAGE_TYPE_HEADER, "Notification")
            .body(publisher.flux())
        val exchange = DefaultServerWebExchange(
            request,
            MockServerHttpResponse(factory),
            DefaultWebSessionManager(),
            ServerCodecConfigurer.create(),
            AcceptHeaderLocaleContextResolver(),
        )
        val pausedScheduler = PausedScheduler()
        val probe = InFlightReplayProbe(pausedScheduler.scheduler)
        try {
            StepVerifier.create(SnsHttpMessageWebFilter(support).filter(exchange, probe.chain))
                .then { publisher.next(source); publisher.complete() }
                .then { probe.awaitQueued(BARRIER_TIMEOUT_SECONDS) }
                .then { pausedScheduler.awaitTask(BARRIER_TIMEOUT_SECONDS) }
                .thenCancel()
                .verify()
            pausedScheduler.release()
            probe.awaitCancellation(BARRIER_TIMEOUT_SECONDS)
        } finally {
            pausedScheduler.close()
        }

        probe.replayBuffers.size shouldBeEqualTo 1
        probe.replaySizes.toList() shouldBeEqualTo listOf(notificationJson.toByteArray().size)
        probe.bodySubscriptions.get() shouldBeEqualTo 1
        probe.activeBodySubscriptions.get() shouldBeEqualTo 0
        probe.downstreamSignal.get() shouldBeEqualTo SignalType.CANCEL
        (source as PooledDataBuffer).isAllocated.shouldBeFalse()
        probe.replayBuffers.forEach { (it as PooledDataBuffer).isAllocated.shouldBeFalse() }
        exchange.response.statusCode shouldBeEqualTo null
        coVerify(exactly = 0) {
            operations.confirmSubscription(any<SnsHttpMessage>(), any())
        }
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

    private class DownstreamCancellationProbe {
        val chainSubscriptions = AtomicInteger()
        val bodySubscriptions = AtomicInteger()
        val activeBodySubscriptions = AtomicInteger()
        val handlerInvocations = AtomicInteger()
        val replayBuffers = mutableListOf<DataBuffer>()
        val replayBodies = mutableListOf<String>()
        private val replayBodyRead = CountDownLatch(1)
        private val bodyTerminated = CountDownLatch(1)
        val downstreamSignal = AtomicReference<SignalType>()
        private val downstreamTerminated = CountDownLatch(1)
        private val handlerGate = Sinks.one<Unit>()

        val chain = WebFilterChain { downstream ->
            chainSubscriptions.incrementAndGet()
            val body = downstream.request.body
                .doOnSubscribe {
                    bodySubscriptions.incrementAndGet()
                    activeBodySubscriptions.incrementAndGet()
                }
                .doFinally {
                    activeBodySubscriptions.decrementAndGet()
                    bodyTerminated.countDown()
                }
                .doOnNext(replayBuffers::add)
            DataBufferUtils.join(body)
                .map { buffer ->
                    try {
                        ByteArray(buffer.readableByteCount()).also(buffer::read).toString(Charsets.UTF_8)
                    } finally {
                        DataBufferUtils.release(buffer)
                    }
                }
                .doOnNext(replayBodies::add)
                .doOnSuccess { replayBodyRead.countDown() }
                .then(
                    handlerGate.asMono()
                        .doOnNext { handlerInvocations.incrementAndGet() }
                        .then(),
                )
                .doFinally {
                    downstreamSignal.set(it)
                    downstreamTerminated.countDown()
                }
        }

        fun awaitReplay(timeoutSeconds: Long) {
            check(replayBodyRead.await(timeoutSeconds, TimeUnit.SECONDS)) {
                "replay body was not read before cancellation"
            }
            check(bodyTerminated.await(timeoutSeconds, TimeUnit.SECONDS)) {
                "replay body subscription was not terminated before cancellation"
            }
        }

        fun awaitCancellation(timeoutSeconds: Long) {
            check(downstreamTerminated.await(timeoutSeconds, TimeUnit.SECONDS)) {
                "downstream subscription was not terminated after cancellation"
            }
        }
    }

    private class InFlightReplayProbe(private val scheduler: Scheduler) {
        val bodySubscriptions = AtomicInteger()
        val activeBodySubscriptions = AtomicInteger()
        val replayBuffers = ConcurrentLinkedQueue<DataBuffer>()
        val replaySizes = ConcurrentLinkedQueue<Int>()
        val downstreamSignal = AtomicReference<SignalType>()
        private val replayQueued = CountDownLatch(1)
        private val bodyTerminated = CountDownLatch(1)
        private val downstreamTerminated = CountDownLatch(1)

        val chain = WebFilterChain { downstream ->
            downstream.request.body
                .doOnSubscribe {
                    bodySubscriptions.incrementAndGet()
                    activeBodySubscriptions.incrementAndGet()
                }
                .doFinally {
                    activeBodySubscriptions.decrementAndGet()
                    bodyTerminated.countDown()
                }
                .doOnNext {
                    replayBuffers.add(it)
                    replaySizes.add(it.readableByteCount())
                    replayQueued.countDown()
                }
                .hide()
                .publishOn(scheduler)
                .then(Mono.never<Void>())
                .doFinally {
                    downstreamSignal.set(it)
                    downstreamTerminated.countDown()
                }
        }

        fun awaitQueued(timeoutSeconds: Long) {
            check(replayQueued.await(timeoutSeconds, TimeUnit.SECONDS)) {
                "replay body was not queued before cancellation"
            }
        }

        fun awaitCancellation(timeoutSeconds: Long) {
            check(bodyTerminated.await(timeoutSeconds, TimeUnit.SECONDS)) {
                "replay body subscription was not terminated after cancellation"
            }
            check(downstreamTerminated.await(timeoutSeconds, TimeUnit.SECONDS)) {
                "downstream subscription was not terminated after cancellation"
            }
        }
    }

    private class PausedScheduler : Scheduler {
        private val task = AtomicReference<Runnable>()
        private val taskStarted = CountDownLatch(1)
        val scheduler: Scheduler = this

        override fun createWorker(): Scheduler.Worker = object : Scheduler.Worker {
            override fun schedule(task: Runnable): Disposable {
                this@PausedScheduler.task.set(task)
                taskStarted.countDown()
                return Disposables.never()
            }

            override fun dispose() = Unit

            override fun isDisposed(): Boolean = false
        }

        override fun schedule(task: Runnable): Disposable = createWorker().schedule(task)

        override fun dispose() = Unit

        override fun isDisposed(): Boolean = false

        fun awaitTask(timeoutSeconds: Long) {
            check(taskStarted.await(timeoutSeconds, TimeUnit.SECONDS)) {
                "replay drain task was not scheduled before cancellation"
            }
        }

        fun release() {
            task.getAndSet(null)?.run()
        }

        fun close() {
            release()
        }
    }

    companion object {
        private const val BARRIER_TIMEOUT_SECONDS = 5L
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
