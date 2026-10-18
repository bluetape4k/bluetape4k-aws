package io.bluetape4k.aws.spring.sns

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.support.toUtf8String
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.MethodParameter
import org.springframework.http.HttpStatus
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.server.ResponseStatusException
import java.io.ByteArrayOutputStream

/** Servlet request cache에 저장된 SNS HTTP envelope에서 handler parameter를 해석합니다. */
class SnsMvcHttpMessageArgumentResolver(
    private val support: SnsHttpMessageResolverSupport,
): HandlerMethodArgumentResolver {

    companion object: KLogging()

    override fun supportsParameter(parameter: MethodParameter): Boolean =
        support.supportsParameter(parameter)

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any? {
        val request = webRequest.getNativeRequest(HttpServletRequest::class.java)
            .requireNotNull { "SNS MVC resolver requires an HttpServletRequest." }

        val message = (request.getAttribute(SnsHttpMessageResolverSupport.REQUEST_ATTRIBUTE) as? SnsHttpMessage)
            ?: readStandalone(request).also {
                request.setAttribute(SnsHttpMessageResolverSupport.REQUEST_ATTRIBUTE, it)
            }
        return support.resolve(parameter, message)
    }

    private fun readStandalone(request: HttpServletRequest): SnsHttpMessage {
        ByteArrayOutputStream().use { body ->
            val buffer = ByteArray(8 * 1024)

            request.inputStream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    body.write(buffer, 0, read)
                    if (body.size() > SnsHttpMessageLimits.MAX_BYTES) {
                        throw ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "SNS HTTP message exceeds maxMessageBytes.",
                        )
                    }
                }
            }
            return support.prepare(
                body.toByteArray().toUtf8String(),
                request.getHeader(SnsHttpMessageResolverSupport.SNS_MESSAGE_TYPE_HEADER),
            )
        }
    }
}
