package io.bluetape4k.aws.sns.model

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.aws.sns.validatePublishBatchRequest
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import software.amazon.awssdk.services.sns.model.PublishBatchRequest
import software.amazon.awssdk.services.sns.model.PublishBatchRequestEntry
import software.amazon.awssdk.services.sns.model.PublishRequest

/**
 * DSL 블록으로 [PublishRequest]를 빌드합니다.
 *
 * ## 동작/계약
 * - [builder] 블록에서 `topicArn`, `message` 등을 직접 설정한다.
 *
 * ```kotlin
 * val req = publishRequest {
 *     topicArn("arn:aws:sns:ap-northeast-2:123456:my-topic")
 *     message("Hello SNS")
 * }
 * ```
 */
inline fun publishRequest(
    builder: PublishRequest.Builder.() -> Unit,
): PublishRequest =
    PublishRequest.builder().apply(builder).build()

/**
 * 토픽 ARN과 메시지로 [PublishRequest]를 생성합니다.
 *
 * ## 동작/계약
 * - [topicArn]이 blank이면 `IllegalArgumentException`을 던진다.
 * - [message]가 blank이면 `IllegalArgumentException`을 던진다.
 * - [snsAttributes]가 null이 아니면 메시지 속성으로 설정된다.
 *
 * ```kotlin
 * val req = publishRequestOf(
 *     topicArn = "arn:aws:sns:ap-northeast-2:123456:my-topic",
 *     message = "Hello SNS"
 * )
 * // req.topicArn().isNotBlank() == true
 * ```
 */
inline fun publishRequestOf(
    topicArn: String,
    message: String,
    snsAttributes: Map<String, MessageAttributeValue>? = null,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: PublishRequest.Builder.() -> Unit = {},
): PublishRequest {
    topicArn.requireNotBlank("topicArn")
    message.requireNotBlank("message")

    return publishRequest {
        topicArn(topicArn)
        message(message)
        snsAttributes?.let { messageAttributes(it) }
        overrideConfiguration?.let { overrideConfiguration(it) }

        builder()
    }
}

/** DSL 블록으로 [PublishBatchRequestEntry]를 빌드합니다. */
inline fun publishBatchRequestEntry(
    builder: PublishBatchRequestEntry.Builder.() -> Unit,
): PublishBatchRequestEntry =
    PublishBatchRequestEntry.builder().apply(builder).build()

/** SNS 배치 발행 항목을 생성하고 로컬 입력 불변식을 검증합니다. */
inline fun publishBatchRequestEntryOf(
    id: String,
    message: String,
    messageAttributes: Map<String, MessageAttributeValue>? = null,
    messageDeduplicationId: String? = null,
    messageGroupId: String? = null,
    builder: PublishBatchRequestEntry.Builder.() -> Unit = {},
): PublishBatchRequestEntry {
    id.requireNotBlank("id")
    message.requireNotBlank("message")

    val requestEntry = publishBatchRequestEntry {
        id(id)
        message(message)
        messageAttributes?.let { this.messageAttributes(it) }
        messageDeduplicationId?.let(::messageDeduplicationId)
        messageGroupId?.let(::messageGroupId)
        builder()
    }
    requestEntry.id().requireNotBlank("entry.id")
    requestEntry.message().requireNotBlank("entry.message")
    return requestEntry
}

/** SNS 배치 발행 요청을 생성하고 SNS의 1..10개·고유 ID 계약을 검증합니다. */
inline fun publishBatchRequestOf(
    topicArn: String,
    entries: List<PublishBatchRequestEntry>,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: PublishBatchRequest.Builder.() -> Unit = {},
): PublishBatchRequest {
    validatePublishBatchRequest(topicArn, entries)

    val request = PublishBatchRequest.builder().apply {
        topicArn(topicArn)
        publishBatchRequestEntries(entries)
        overrideConfiguration?.let(::overrideConfiguration)
        builder()
    }.build()
    request.validatePublishBatchRequest()
    return request
}
