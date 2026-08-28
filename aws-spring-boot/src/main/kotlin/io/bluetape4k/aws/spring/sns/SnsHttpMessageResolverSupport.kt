package io.bluetape4k.aws.spring.sns

import io.bluetape4k.aws.spring.sns.annotation.handlers.NotificationMessage
import io.bluetape4k.aws.spring.sns.annotation.handlers.NotificationMessageAttributes
import io.bluetape4k.aws.spring.sns.annotation.handlers.NotificationRawMessage
import io.bluetape4k.aws.spring.sns.annotation.handlers.NotificationSubject
import io.bluetape4k.aws.spring.sns.annotation.endpoint.NotificationMessageMapping
import io.bluetape4k.aws.spring.sns.annotation.endpoint.NotificationSubscriptionMapping
import io.bluetape4k.aws.spring.sns.annotation.endpoint.NotificationUnsubscribeConfirmationMapping
import io.bluetape4k.aws.spring.sns.handlers.NotificationStatus
import io.bluetape4k.aws.spring.sqs.SnsMessageAttribute
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.MethodParameter
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.lang.reflect.ParameterizedType

/** MVC와 WebFlux adapter가 공유하는 SNS HTTP 정책과 handler parameter 매핑을 제공합니다. */
class SnsHttpMessageResolverSupport(
    private val properties: SnsHttpEndpointProperties = SnsHttpEndpointProperties(),
    private val verifierProvider: ObjectProvider<SnsHttpMessageVerifier>? = null,
    objectMapper: Any? = null,
    private val operations: SnsOperations? = null,
) {

    private val payloadConverter = SnsHttpMessagePayloadConverter(objectMapper)

    /**
     * Handler 호출 전에 SNS HTTP JSON을 파싱하고 구성된 topic/verifier 경계를 적용합니다.
     *
     * `SnsHttpMessageParser`가 envelope와 `MessageAttributes` 구조를 검증한 뒤 topic ARN
     * allowlist를 확인하고, `verificationRequired`가 활성화된 경우
     * `SnsHttpMessageVerifier`로 원문 JSON의 서명을 검증합니다.
     *
     * @param json 파싱하고 검증할 원문 SNS HTTP JSON입니다.
     * @param messageTypeHeader 선택적으로 함께 확인할 `x-amz-sns-message-type` 헤더 값입니다.
     * @return handler가 사용할 수 있도록 준비된 [SnsHttpMessage]입니다.
     */
    fun prepare(json: String, messageTypeHeader: String?): SnsHttpMessage {
        val message = SnsHttpMessageParser.parse(json, messageTypeHeader)
        // handler가 envelope를 관찰하기 전에 MessageAttributes 구조를 검증합니다.
        message.messageAttributes
        if (!isTopicAllowed(message.topicArn)) {
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "SNS HTTP message TopicArn is not allowed.",
            )
        }
        if (properties.verificationRequired) {
            val verifier = verifierProvider?.getIfAvailable()
                ?: throw ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "SNS HTTP message verifier is not configured.",
                )
            verifier.verify(json, messageTypeHeader, expectedTopicArn = message.topicArn)
        }
        return message
    }

    /** SNS HTTP adapter가 주어진 Spring [MethodParameter]를 지원하는지 검증합니다. */
    fun supportsParameter(parameter: MethodParameter): Boolean {
        validateAnnotationCombination(parameter)
        validateDirectParameterType(parameter.parameterType)
        val supported =
            parameter.hasParameterAnnotation(NotificationMessage::class.java) ||
            parameter.hasParameterAnnotation(NotificationSubject::class.java) ||
            parameter.hasParameterAnnotation(NotificationMessageAttributes::class.java) ||
            parameter.hasParameterAnnotation(NotificationRawMessage::class.java) ||
            parameter.parameterType == SnsHttpMessage::class.java ||
            parameter.parameterType == NotificationStatus::class.java
        if (supported) validateStaticParameter(parameter)
        return supported
    }

    /** 애플리케이션 시작 시 SNS HTTP mapping과 handler parameter 조합을 검증합니다. */
    fun validateHandlerMethod(method: java.lang.reflect.Method) {
        val mappingType = resolveSnsHttpEndpointMessageType(method)
        method.parameters.indices
            .map { index -> MethodParameter(method, index) }
            .forEach { parameter ->
                supportsParameter(parameter)
                validateSnsHttpEndpointMappingParameter(parameter, mappingType)
            }
    }

    /**
     * 이미 파싱된 [SnsHttpMessage]에서 지원되는 handler parameter 값을 하나 해석합니다.
     *
     * `@NotificationMessage`, `@NotificationSubject`, `@NotificationMessageAttributes`,
     * `@NotificationRawMessage`, `SnsHttpMessage`, `NotificationStatus`의 선언 방식과
     * 메시지 타입에 따라 typed payload, subject, attributes, 원본 envelope 또는 상태 객체를
     * 반환합니다. `NotificationStatus`는 confirmation 메시지에서만 제공되며 AWS 확인 작업은
     * handler가 `confirmSubscription()`을 명시적으로 호출할 때 수행됩니다.
     *
     * @param parameter 값을 주입할 handler parameter 메타데이터입니다.
     * @param message topic과 verifier 정책을 통과한 SNS HTTP envelope입니다.
     * @return parameter에 주입할 값입니다.
     */
    fun resolve(parameter: MethodParameter, message: SnsHttpMessage): Any? {
        val parameterType = parameter.parameterType
        return when {
            parameter.hasParameterAnnotation(NotificationMessage::class.java) -> {
                requireNotificationType(message)
                requireConcretePayloadType(parameter)
                payloadConverter.convert(
                    message = message.message,
                    targetType = parameterType,
                    nestedContentType = message.messageAttributes[CONTENT_TYPE_ATTRIBUTE]?.value,
                )
            }
            parameter.hasParameterAnnotation(NotificationSubject::class.java) -> {
                requireNotificationType(message)
                check(parameterType == String::class.java) {
                    "@NotificationSubject requires a String parameter."
                }
                message.subject
            }
            parameter.hasParameterAnnotation(NotificationMessageAttributes::class.java) -> {
                check(Map::class.java.isAssignableFrom(parameterType)) {
                    "@NotificationMessageAttributes requires a Map parameter."
                }
                message.messageAttributes
            }
            parameter.hasParameterAnnotation(NotificationRawMessage::class.java) ||
                SnsHttpMessage::class.java.isAssignableFrom(parameterType) -> {
                    check(parameterType == SnsHttpMessage::class.java) {
                        "@NotificationRawMessage requires an SnsHttpMessage parameter."
                    }
                    message
                }
            parameterType == NotificationStatus::class.java -> {
                requireConfirmationType(message)
                val operations = checkNotNull(operations) {
                    "NotificationStatus requires an SnsOperations bean."
                }
                SnsNotificationStatus(message, operations)
            }
            else -> error("Unsupported SNS HTTP handler parameter: ${parameter.parameterName ?: parameterType.name}.")
        }
    }

    private fun isTopicAllowed(topicArn: String): Boolean =
        (properties.expectedTopicArns.isNotEmpty() && topicArn in properties.expectedTopicArns) ||
            (properties.expectedTopicArns.isEmpty() && properties.allowStructuralOnly)

    private fun requireNotificationType(message: SnsHttpMessage) {
        check(message.isNotification) {
            "The annotated parameter is only valid for Notification messages."
        }
    }

    private fun requireConfirmationType(message: SnsHttpMessage) {
        check(message.canConfirmSubscription) {
            "NotificationStatus is only valid for confirmation messages."
        }
    }

    private fun requireConcretePayloadType(parameter: MethodParameter) {
        check(parameter.genericParameterType !is ParameterizedType || parameter.parameterType == String::class.java) {
            "@NotificationMessage does not support parameterized generic targets."
        }
    }

    private fun validateStaticParameter(parameter: MethodParameter) {
        val type = parameter.parameterType
        when {
            parameter.hasParameterAnnotation(NotificationMessage::class.java) -> {
                check(type != Void.TYPE) { "@NotificationMessage requires a concrete parameter type." }
                requireConcretePayloadType(parameter)
            }
            parameter.hasParameterAnnotation(NotificationSubject::class.java) ->
                check(type == String::class.java) { "@NotificationSubject requires a String parameter." }
            parameter.hasParameterAnnotation(NotificationMessageAttributes::class.java) -> {
                check(Map::class.java.isAssignableFrom(type)) {
                    "@NotificationMessageAttributes requires a Map parameter."
                }
                val generic = parameter.genericParameterType as? ParameterizedType
                check(generic != null) { "@NotificationMessageAttributes requires Map<String, SnsMessageAttribute>." }
                check(generic.actualTypeArguments.getOrNull(0) == String::class.java) {
                    "@NotificationMessageAttributes requires String keys."
                }
                check(generic.actualTypeArguments.getOrNull(1) == SnsMessageAttribute::class.java) {
                    "@NotificationMessageAttributes requires SnsMessageAttribute values."
                }
            }
            parameter.hasParameterAnnotation(NotificationRawMessage::class.java) ->
                check(type == SnsHttpMessage::class.java) {
                    "@NotificationRawMessage requires an SnsHttpMessage parameter."
                }
        }
    }

    private fun validateAnnotationCombination(parameter: MethodParameter) {
        val annotationCount = listOf(
            NotificationMessage::class.java,
            NotificationSubject::class.java,
            NotificationMessageAttributes::class.java,
            NotificationRawMessage::class.java,
        ).count { annotation -> parameter.hasParameterAnnotation(annotation) }
        check(annotationCount <= 1) {
            "An SNS HTTP handler parameter must declare at most one adapter annotation."
        }
    }

    private fun validateDirectParameterType(type: Class<*>) {
        if (SnsHttpMessage::class.java.isAssignableFrom(type)) {
            check(type == SnsHttpMessage::class.java) {
                "SNS HTTP raw envelope parameters must use SnsHttpMessage exactly."
            }
        }
        if (NotificationStatus::class.java.isAssignableFrom(type)) {
            check(type == NotificationStatus::class.java) {
                "SNS HTTP status parameters must use NotificationStatus exactly."
            }
        }
    }

    companion object {
        const val REQUEST_ATTRIBUTE: String = "io.bluetape4k.aws.spring.sns.message"
        const val WEBFLUX_MESSAGE_ATTRIBUTE: String = "io.bluetape4k.aws.spring.sns.message.mono"
        const val CONTENT_TYPE_ATTRIBUTE: String = "contentType"
        const val SNS_MESSAGE_TYPE_HEADER: String = SnsHttpMessageParser.MESSAGE_TYPE_HEADER
    }

}

private enum class SnsHttpEndpointMessageType {
    NOTIFICATION,
    CONFIRMATION,
}

private fun resolveSnsHttpEndpointMessageType(method: java.lang.reflect.Method): SnsHttpEndpointMessageType? {
    val mappingTypes = sequenceOf(method, method.declaringClass)
        .flatMap { element ->
            buildList {
                if (AnnotatedElementUtils.hasAnnotation(element, NotificationMessageMapping::class.java)) {
                    add(SnsHttpEndpointMessageType.NOTIFICATION)
                }
                if (AnnotatedElementUtils.hasAnnotation(element, NotificationSubscriptionMapping::class.java)) {
                    add(SnsHttpEndpointMessageType.CONFIRMATION)
                }
                if (
                    AnnotatedElementUtils.hasAnnotation(
                        element,
                        NotificationUnsubscribeConfirmationMapping::class.java,
                    )
                ) {
                    add(SnsHttpEndpointMessageType.CONFIRMATION)
                }
            }.asSequence()
        }
        .toSet()
    check(mappingTypes.size <= 1) {
        "An SNS HTTP handler must not combine notification and confirmation mappings."
    }
    return mappingTypes.firstOrNull()
}

private fun validateSnsHttpEndpointMappingParameter(
    parameter: MethodParameter,
    mappingType: SnsHttpEndpointMessageType?,
) {
    when (mappingType) {
        SnsHttpEndpointMessageType.NOTIFICATION -> {
            check(parameter.parameterType != NotificationStatus::class.java) {
                "NotificationStatus is only valid with confirmation mappings."
            }
        }

        SnsHttpEndpointMessageType.CONFIRMATION -> {
            val notificationParameter =
                parameter.hasParameterAnnotation(NotificationMessage::class.java) ||
                    parameter.hasParameterAnnotation(NotificationSubject::class.java) ||
                    parameter.hasParameterAnnotation(NotificationMessageAttributes::class.java)
            check(!notificationParameter) {
                "Notification message parameters are only valid with NotificationMessageMapping."
            }
        }

        null -> Unit
    }
}
