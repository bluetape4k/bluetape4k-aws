package io.bluetape4k.aws.spring.sns

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.server.ResponseStatusException
import java.lang.reflect.Method

/** annotation handler parameter의 중첩 SNS Message를 원문 또는 JSON 타입으로 변환합니다. */
class SnsHttpMessagePayloadConverter(
    private val objectMapper: Any? = null,
) {

    private val readValueMethod: Method? by lazy {
        objectMapper?.javaClass?.methods?.firstOrNull { method ->
            method.name == "readValue" &&
                method.parameterTypes.contentEquals(arrayOf(String::class.java, Class::class.java))
        }
    }

    @Suppress("ThrowsCount")
    fun convert(
        message: String,
        targetType: Class<*>,
        nestedContentType: String?,
    ): Any {
        if (targetType == String::class.java) return message
        if (!nestedContentType.isJsonMediaType()) {
            throw badRequest("Typed SNS messages require an application/json nested content type.")
        }
        val mapper = objectMapper
            ?: throw badRequest("An ObjectMapper is required for typed SNS message parameters.")
        return runCatching {
            readValueMethod
                ?.invoke(mapper, message, targetType)
                ?: error("Configured ObjectMapper does not expose readValue(String, Class).")
        }
            .getOrElse { cause -> throw badRequest("SNS message payload conversion failed.", cause) }
    }

    @Suppress("ReturnCount")
    private fun String?.isJsonMediaType(): Boolean {
        if (this.isNullOrBlank()) return false
        val mediaType = runCatching { MediaType.parseMediaType(this) }.getOrNull() ?: return false
        return mediaType.type.equals("application", ignoreCase = true) &&
            (mediaType.subtype.equals("json", ignoreCase = true) ||
                mediaType.subtype.endsWith("+json", ignoreCase = true))
    }

    private fun badRequest(message: String, cause: Throwable? = null): ResponseStatusException =
        ResponseStatusException(HttpStatus.BAD_REQUEST, message, cause)
}

internal fun findSnsObjectMapper(beanFactory: org.springframework.beans.factory.ListableBeanFactory): Any? {
    val mapperType = runCatching {
        Class.forName("tools.jackson.databind.ObjectMapper", false, beanFactory.javaClass.classLoader)
    }.getOrNull() ?: return null
    return beanFactory.getBeansOfType(mapperType).values.firstOrNull()
}
