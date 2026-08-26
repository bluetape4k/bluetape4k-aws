package io.bluetape4k.aws.spring.sns

import org.springframework.core.MethodParameter
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.web.reactive.BindingContext
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

/** Resolves SNS HTTP handler parameters from the filter-owned exchange cache. */
class SnsWebFluxHttpMessageArgumentResolver(
    private val support: SnsHttpMessageResolverSupport,
) : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter): Boolean =
        support.supportsParameter(parameter)

    override fun resolveArgument(
        parameter: MethodParameter,
        bindingContext: BindingContext,
        exchange: ServerWebExchange,
    ): Mono<Any> {
        val cached = exchange.getAttribute<Mono<SnsHttpMessage>>(
            SnsHttpMessageResolverSupport.WEBFLUX_MESSAGE_ATTRIBUTE,
        )
        return cached?.flatMap { message -> Mono.justOrEmpty(support.resolve(parameter, message)) }
            ?: readStandalone(exchange).flatMap { message -> Mono.justOrEmpty(support.resolve(parameter, message)) }
    }

    private fun readStandalone(exchange: ServerWebExchange): Mono<SnsHttpMessage> {
        exchange.getAttribute<Mono<SnsHttpMessage>>(SnsHttpMessageResolverSupport.WEBFLUX_MESSAGE_ATTRIBUTE)
            ?.let { return it }
        val prepared = DataBufferUtils.join(exchange.request.body, SnsHttpMessageLimits.MAX_READ_BYTES)
            .flatMap { joined ->
                val bytes = try {
                    ByteArray(joined.readableByteCount()).also { joined.read(it) }
                } finally {
                    DataBufferUtils.release(joined)
                }
                Mono.fromCallable {
                    support.prepare(
                        bytes.toString(Charsets.UTF_8),
                        exchange.request.headers.getFirst(SnsHttpMessageResolverSupport.SNS_MESSAGE_TYPE_HEADER),
                    )
                }.subscribeOn(Schedulers.boundedElastic())
            }
            .doOnDiscard(DataBuffer::class.java, DataBufferUtils.releaseConsumer())
            .cache()
        exchange.attributes[SnsHttpMessageResolverSupport.WEBFLUX_MESSAGE_ATTRIBUTE] = prepared
        return prepared
    }
}
