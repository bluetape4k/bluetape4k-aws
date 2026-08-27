package io.bluetape4k.aws.spring.sns

import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.server.reactive.ServerHttpRequestDecorator
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.Exceptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.util.concurrent.CancellationException

/** Performs the SNS gate before a reactive handler and supplies a replayable body. */
class SnsHttpMessageWebFilter(
    private val support: SnsHttpMessageResolverSupport,
) : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val header = exchange.request.headers.getFirst(SnsHttpMessageResolverSupport.SNS_MESSAGE_TYPE_HEADER)
        if (header.isNullOrBlank()) return chain.filter(exchange)

        val prepared: Mono<Pair<SnsHttpMessage, ByteArray>> =
            DataBufferUtils.join(exchange.request.body, SnsHttpMessageLimits.MAX_READ_BYTES)
            .flatMap { joined ->
                val bytes = try {
                    ByteArray(joined.readableByteCount()).also { joined.read(it) }
                } finally {
                    DataBufferUtils.release(joined)
                }
                Mono.fromCallable {
                    support.prepare(bytes.toString(Charsets.UTF_8), header) to bytes
                }.subscribeOn(Schedulers.boundedElastic())
            }
            .onErrorResume { cause ->
                if (cause is CancellationException || Exceptions.isCancel(cause)) {
                    Mono.error<Pair<SnsHttpMessage, ByteArray>>(cause)
                } else {
                    val decision = SnsHttpEndpointErrorPolicy.classify(cause)
                    SnsHttpEndpointErrorPolicy.record(decision, messageType = header)
                    exchange.response.statusCode = org.springframework.http.HttpStatusCode.valueOf(decision.status)
                    exchange.response.setComplete().then(Mono.empty())
                }
            }

        return prepared.flatMap { (message, bytes) ->
                val cached = Mono.just(message).cache()
                exchange.attributes[SnsHttpMessageResolverSupport.WEBFLUX_MESSAGE_ATTRIBUTE] = cached
                val decoratedRequest = object : ServerHttpRequestDecorator(exchange.request) {
                    override fun getBody(): Flux<DataBuffer> =
                        Flux.defer {
                            Flux.just(exchange.response.bufferFactory().wrap(bytes.copyOf()))
                        }.doOnDiscard(DataBuffer::class.java, DataBufferUtils.releaseConsumer())

                    override fun getHeaders(): HttpHeaders =
                        HttpHeaders().also { headers ->
                            headers.putAll(super.getHeaders())
                            headers.contentLength = bytes.size.toLong()
                        }
                }
                chain.filter(exchange.mutate().request(decoratedRequest).build())
            }
            .doOnDiscard(DataBuffer::class.java, DataBufferUtils.releaseConsumer())
    }
}
