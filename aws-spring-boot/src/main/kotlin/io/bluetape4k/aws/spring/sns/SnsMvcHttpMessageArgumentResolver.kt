package io.bluetape4k.aws.spring.sns

import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.MethodParameter
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import java.io.ByteArrayOutputStream

/** Resolves SNS HTTP handler parameters from the servlet request cache. */
class SnsMvcHttpMessageArgumentResolver(
    private val support: SnsHttpMessageResolverSupport,
) : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter): Boolean =
        support.supportsParameter(parameter)

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any? {
        val request = requireNotNull(webRequest.getNativeRequest(HttpServletRequest::class.java)) {
            "SNS MVC resolver requires an HttpServletRequest."
        }
        val message = (request.getAttribute(SnsHttpMessageResolverSupport.REQUEST_ATTRIBUTE) as? SnsHttpMessage)
            ?: readStandalone(request).also {
                request.setAttribute(SnsHttpMessageResolverSupport.REQUEST_ATTRIBUTE, it)
            }
        return support.resolve(parameter, message)
    }

    private fun readStandalone(request: HttpServletRequest): SnsHttpMessage {
        val body = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        request.inputStream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                body.write(buffer, 0, read)
                if (body.size() > SnsHttpMessageLimits.MAX_BYTES) {
                    throw org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "SNS HTTP message exceeds maxMessageBytes.",
                    )
                }
            }
        }
        return support.prepare(
            body.toByteArray().toString(Charsets.UTF_8),
            request.getHeader(SnsHttpMessageResolverSupport.SNS_MESSAGE_TYPE_HEADER),
        )
    }
}
