package io.bluetape4k.aws.spring.sns

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.error
import io.bluetape4k.logging.warn
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import software.amazon.awssdk.core.exception.SdkClientException

internal object SnsHttpEndpointErrorPolicy: KLogging() {

    private const val SERVER_ERROR_THRESHOLD = 500

    fun classify(cause: Throwable): Decision = when {
        cause.causesContain<DataBufferLimitException>() || cause.causesContain<SnsHttpBodyLimitException>() ->
            Decision(HttpStatus.BAD_REQUEST.value(), "body-limit")

        cause is ResponseStatusException ->
            Decision(cause.statusCode.value(), "configured-policy")

        cause is IllegalArgumentException ->
            Decision(HttpStatus.BAD_REQUEST.value(), "invalid-input")

        cause is SdkClientException ->
            Decision(HttpStatus.SERVICE_UNAVAILABLE.value(), "verification-dependency")

        else ->
            Decision(HttpStatus.INTERNAL_SERVER_ERROR.value(), "internal-error")
    }

    fun record(decision: Decision, size: Int? = null, messageType: String? = null) {
        val category = decision.category
        val fields = buildList {
            add("category=$category")
            add("status=${decision.status}")
            size?.let { add("size=${it.coerceIn(0, SnsHttpMessageLimits.MAX_READ_BYTES)}") }
            messageType?.let { add("type=${safeMessageType(it)}") }
        }.joinToString(separator = " ")

        if (decision.status >= SERVER_ERROR_THRESHOLD) log.error { "SNS HTTP request rejected: $fields" }
        else log.warn { "SNS HTTP request rejected: $fields" }
    }

    data class Decision(val status: Int, val category: String)

    private inline fun <reified T: Throwable> Throwable.causesContain(): Boolean =
        generateSequence(this) { it.cause }.any { it is T }

    private fun safeMessageType(messageType: String): String =
        when (messageType) {
            "Notification", "SubscriptionConfirmation", "UnsubscribeConfirmation" -> messageType
            else -> "unknown"
        }
}

internal class SnsHttpBodyLimitException(val observedSize: Int):
    ResponseStatusException(HttpStatus.BAD_REQUEST, "SNS HTTP message exceeds maxMessageBytes.")
