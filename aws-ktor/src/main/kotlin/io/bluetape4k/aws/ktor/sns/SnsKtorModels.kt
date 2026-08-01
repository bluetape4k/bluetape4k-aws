package io.bluetape4k.aws.ktor.sns

import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import java.io.Serializable
import java.net.URI

/**
 * SNS FIFO 주제의 처리량 범위입니다.
 */
enum class SnsFifoThroughputScope(val attributeValue: String) {
    /** 전체 주제 수준에서 FIFO 처리량을 계산합니다. */
    TOPIC("Topic"),

    /** 메시지 그룹 수준에서 FIFO 처리량을 계산합니다. */
    MESSAGE_GROUP("MessageGroup"),
}

/**
 * `AWS.SNS.SMS.SMSType` 메시지 속성에 사용할 Amazon SNS SMS 전송 타입입니다.
 */
enum class SnsSmsType(val attributeValue: String) {
    /** 마케팅 알림처럼 중요하지 않고 비용에 최적화된 메시지입니다. */
    PROMOTIONAL("Promotional"),

    /** 일회용 비밀번호나 계정 알림처럼 안정성에 최적화된 메시지입니다. */
    TRANSACTIONAL("Transactional"),
}

/**
 * SNS 주제 게시 요청 값 객체입니다.
 *
 * ## 계약
 *
 * FIFO 전용 필드는 `.fifo` 주제 ARN에만 허용됩니다. FIFO 주제에는 비어 있지 않은
 * 메시지 그룹 id가 필요합니다.
 */
data class SnsPublishRequest(
    val topicArn: String,
    val message: String,
    val subject: String? = null,
    val messageAttributes: Map<String, MessageAttributeValue> = emptyMap(),
    val messageGroupId: String? = null,
    val messageDeduplicationId: String? = null,
): Serializable {
    init {
        topicArn.requireTopicArn()
        require(message.isNotBlank()) { "message must not be blank." }
        subject?.let { require(it.isNotBlank()) { "subject must not be blank." } }
        messageGroupId?.let { require(it.isNotBlank()) { "messageGroupId must not be blank." } }
        messageDeduplicationId?.let { require(it.isNotBlank()) { "messageDeduplicationId must not be blank." } }

        val fifo = topicArn.endsWith(".fifo")
        if (fifo) {
            require(!messageGroupId.isNullOrBlank()) {
                "messageGroupId is required for FIFO topic."
            }
        } else {
            require(messageGroupId == null && messageDeduplicationId == null) {
                "messageGroupId and messageDeduplicationId are not allowed for standard topic."
            }
        }
    }

    companion object {
        private const val serialVersionUID: Long = 5795804169725499052L
    }
}

/**
 * 전화번호로 SMS 메시지를 직접 게시하는 값 객체입니다.
 *
 * ## 계약
 *
 * 명시적인 SMS 옵션을 Amazon SNS SMS 메시지 속성에 매핑합니다. 전화번호는
 * `+15550100000`과 같은 E.164 형식을 사용해야 합니다.
 */
data class SnsSmsRequest(
    val phoneNumber: String,
    val message: String,
    val smsType: SnsSmsType? = null,
    val senderId: String? = null,
    val maxPrice: String? = null,
    val originationNumber: String? = null,
    val entityId: String? = null,
    val templateId: String? = null,
    val messageAttributes: Map<String, MessageAttributeValue> = emptyMap(),
): Serializable {

    init {
        require(phoneNumber.isNotBlank()) { "phoneNumber must not be blank." }
        require(message.isNotBlank()) { "message must not be blank." }
        senderId?.let { require(it.isNotBlank()) { "senderId must not be blank." } }
        maxPrice?.let { require(it.isNotBlank()) { "maxPrice must not be blank." } }
        originationNumber?.let { require(it.isNotBlank()) { "originationNumber must not be blank." } }
        entityId?.let { require(it.isNotBlank()) { "entityId must not be blank." } }
        templateId?.let { require(it.isNotBlank()) { "templateId must not be blank." } }
    }

    internal fun toMessageAttributes(): Map<String, MessageAttributeValue> =
        buildMap {
            putAll(messageAttributes)
            smsType?.let { put(SMS_TYPE_ATTRIBUTE, stringAttribute(it.attributeValue)) }
            senderId?.let { put(SENDER_ID_ATTRIBUTE, stringAttribute(it)) }
            maxPrice?.let { put(MAX_PRICE_ATTRIBUTE, stringAttribute(it)) }
            originationNumber?.let { put(ORIGINATION_NUMBER_ATTRIBUTE, stringAttribute(it)) }
            entityId?.let { put(ENTITY_ID_ATTRIBUTE, stringAttribute(it)) }
            templateId?.let { put(TEMPLATE_ID_ATTRIBUTE, stringAttribute(it)) }
        }

    companion object {
        private const val serialVersionUID: Long = -8689631020482660250L

        const val SMS_TYPE_ATTRIBUTE: String = "AWS.SNS.SMS.SMSType"
        const val SENDER_ID_ATTRIBUTE: String = "AWS.SNS.SMS.SenderID"
        const val MAX_PRICE_ATTRIBUTE: String = "AWS.SNS.SMS.MaxPrice"
        const val ORIGINATION_NUMBER_ATTRIBUTE: String = "AWS.MM.SMS.OriginationNumber"
        const val ENTITY_ID_ATTRIBUTE: String = "AWS.MM.SMS.EntityId"
        const val TEMPLATE_ID_ATTRIBUTE: String = "AWS.MM.SMS.TemplateId"

        private fun stringAttribute(value: String): MessageAttributeValue =
            MessageAttributeValue.builder()
                .dataType("String")
                .stringValue(value)
                .build()
    }
}

/**
 * SNS HTTP(S) 엔드포인트 메시지 타입입니다.
 */
enum class SnsHttpMessageType(val value: String) {
    /** 구독한 HTTP(S) 엔드포인트로 전달되는 메시지입니다. */
    NOTIFICATION("Notification"),

    /** HTTP(S) 엔드포인트 구독 후 확인을 위해 전송되는 메시지입니다. */
    SUBSCRIPTION_CONFIRMATION("SubscriptionConfirmation"),

    /** HTTP(S) 엔드포인트 구독 해제 후 재확인을 위해 전송되는 메시지입니다. */
    UNSUBSCRIBE_CONFIRMATION("UnsubscribeConfirmation"),
    ;

    companion object {
        /** 공식 SNS HTTP 메시지 타입 값을 해석합니다. */
        fun from(value: String): SnsHttpMessageType =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unsupported SNS HTTP message type: $value")
    }
}

/**
 * 파싱된 SNS HTTP(S) 엔드포인트 메시지입니다.
 *
 * ## 계약
 *
 * 이 값은 신뢰되지 않습니다. SNS 서명 필드를 노출하지만 암호학적 서명을 검증하지 않습니다.
 * 알림을 처리하거나 구독을 확인하기 전에 인증서 체인, 서명, 예상 topic ARN을 검증하세요.
 */
data class SnsHttpMessage(
    val type: SnsHttpMessageType,
    val messageId: String,
    val topicArn: String,
    val message: String,
    val timestamp: String,
    val signatureVersion: String,
    val signature: String,
    val signingCertUrl: URI,
    val subject: String? = null,
    val token: String? = null,
    val subscribeUrl: URI? = null,
    val unsubscribeUrl: URI? = null,
    val raw: Map<String, String?> = emptyMap(),
): Serializable {

    /** 알림 전달 메시지이면 true입니다. */
    val isNotification: Boolean
        get() = type == SnsHttpMessageType.NOTIFICATION

    /** 구독 확인 메시지이면 true입니다. */
    val isSubscriptionConfirmation: Boolean
        get() = type == SnsHttpMessageType.SUBSCRIPTION_CONFIRMATION

    /** 구독 해제 확인 메시지이면 true입니다. */
    val isUnsubscribeConfirmation: Boolean
        get() = type == SnsHttpMessageType.UNSUBSCRIBE_CONFIRMATION

    /** 구독 확인 토큰을 전달할 수 있는 메시지 타입이면 true입니다. */
    val canConfirmSubscription: Boolean
        get() = type == SnsHttpMessageType.SUBSCRIPTION_CONFIRMATION ||
            type == SnsHttpMessageType.UNSUBSCRIBE_CONFIRMATION

    internal fun requireConfirmationToken(): String {
        require(canConfirmSubscription) {
            "SNS HTTP message type ${type.value} cannot confirm a subscription."
        }
        return requireNotNull(token) {
            "SNS HTTP confirmation message token must not be null."
        }
    }

    companion object {
        private const val serialVersionUID: Long = -4940905748188566685L
    }
}

/**
 * 호출자가 검증한 SNS HTTP 메시지입니다.
 *
 * ## 계약
 *
 * 호출자가 SNS 서명, 인증서 체인, 예상 topic ARN, 재생 정책을 검증한 뒤에만
 * 이 래퍼를 생성하세요.
 */
class TrustedSnsHttpMessage private constructor(
    val message: SnsHttpMessage,
): Serializable {

    companion object {
        private const val serialVersionUID: Long = -7318834693375493029L

        /**
         * 호출자가 검증한 SNS HTTP 메시지를 감쌉니다.
         */
        fun fromVerified(message: SnsHttpMessage): TrustedSnsHttpMessage =
            TrustedSnsHttpMessage(message)
    }
}

internal fun String.requireTopicArn() {
    require(isNotBlank()) { "topicArn must not be blank." }
}

internal fun String.requireTopicName() {
    require(isNotBlank()) { "topicName must not be blank." }
}
