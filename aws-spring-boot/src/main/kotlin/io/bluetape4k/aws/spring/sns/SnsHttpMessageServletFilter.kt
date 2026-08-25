package io.bluetape4k.aws.spring.sns

import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import jakarta.servlet.FilterChain

/** Performs the SNS structural/security gate and replays the bounded servlet body. */
class SnsHttpMessageServletFilter(
    private val support: SnsHttpMessageResolverSupport,
) : OncePerRequestFilter() {

    @Suppress("ReturnCount", "SwallowedException", "TooGenericExceptionCaught")
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val header = request.getHeader(SnsHttpMessageResolverSupport.SNS_MESSAGE_TYPE_HEADER)
        if (header.isNullOrBlank()) {
            filterChain.doFilter(request, response)
            return
        }
        val body = try {
            readBounded(request)
        } catch (cause: kotlinx.coroutines.CancellationException) {
            throw cause
        } catch (cause: RuntimeException) {
            reject(response, cause, (cause as? SnsHttpBodyLimitException)?.observedSize, header)
            return
        }
        val message = try {
            support.prepare(body.toString(StandardCharsets.UTF_8), header)
        } catch (cause: kotlinx.coroutines.CancellationException) {
            throw cause
        } catch (cause: RuntimeException) {
            reject(response, cause, body.size, header)
            return
        }
        request.setAttribute(SnsHttpMessageResolverSupport.REQUEST_ATTRIBUTE, message)
        filterChain.doFilter(ReplayableRequest(request, body), response)
    }

    private fun readBounded(request: HttpServletRequest): ByteArray {
        if (request.contentLengthLong > SnsHttpMessageLimits.MAX_BYTES) {
            val observedSize = request.contentLengthLong
                .coerceAtMost(SnsHttpMessageLimits.MAX_READ_BYTES.toLong())
                .toInt()
            throw SnsHttpBodyLimitException(observedSize)
        }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        request.inputStream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                if (output.size() > SnsHttpMessageLimits.MAX_BYTES) {
                    throw SnsHttpBodyLimitException(output.size().coerceAtMost(SnsHttpMessageLimits.MAX_READ_BYTES))
                }
            }
        }
        return output.toByteArray()
    }

    private fun sendError(response: HttpServletResponse, status: Int) {
        if (!response.isCommitted) response.sendError(status)
    }

    private fun reject(
        response: HttpServletResponse,
        cause: RuntimeException,
        size: Int?,
        messageType: String,
    ) {
        val decision = SnsHttpEndpointErrorPolicy.classify(cause)
        SnsHttpEndpointErrorPolicy.record(decision, size, messageType)
        sendError(response, decision.status)
    }
}

private class ReplayableRequest(
    request: HttpServletRequest,
    private val bytes: ByteArray,
) : HttpServletRequestWrapper(request) {
    override fun getInputStream(): ServletInputStream =
        object : ServletInputStream() {
            private val input = ByteArrayInputStream(bytes)

            override fun read(): Int = input.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, len)
            override fun isFinished(): Boolean = input.available() == 0
            override fun isReady(): Boolean = true
            override fun setReadListener(readListener: ReadListener?) {
                if (readListener == null) return
                try {
                    if (input.available() > 0) readListener.onDataAvailable()
                    if (input.available() == 0) readListener.onAllDataRead()
                } catch (cause: IOException) {
                    readListener.onError(cause)
                }
            }
        }

    override fun getReader(): BufferedReader =
        BufferedReader(InputStreamReader(inputStream, characterEncoding ?: StandardCharsets.UTF_8.name()))

    override fun getContentLength(): Int = bytes.size
    override fun getContentLengthLong(): Long = bytes.size.toLong()
}
