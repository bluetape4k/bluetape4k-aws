package io.bluetape4k.aws.sqs.model

import io.bluetape4k.aws.sqs.validateSqsDelaySeconds
import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry
import software.amazon.awssdk.services.sqs.model.SendMessageRequest

/**
 * [SendMessageRequest] 를 생성합니다.
 *
 * @param builder [SendMessageRequest.Builder]를 이용하여 [SendMessageRequest]를 초기화하는 람다입니다.
 *
 * ```kotlin
 * val request = sendMessageRequest {
 *     queueUrl("https://sqs.ap-northeast-2.amazonaws.com/123/my-queue")
 *     messageBody("hello")
 * }
 * // request.messageBody() == "hello"
 * ```
 */
inline fun sendMessageRequest(
    builder: SendMessageRequest.Builder.() -> Unit,
): SendMessageRequest {
    return SendMessageRequest.builder().apply(builder).build()
}

/**
 * [queueUrl], [messageBody]로 [SendMessageRequest]를 생성합니다.
 *
 * [delaySeconds]를 지정하면 SQS 제약(0..900)을 선검증합니다.
 *
 * ```kotlin
 * val request = sendMessageRequestOf(
 *     queueUrl = "https://sqs.ap-northeast-2.amazonaws.com/123/my-queue",
 *     messageBody = "hello",
 *     delaySeconds = 5
 * )
 * // request.delaySeconds() == 5
 * ```
 */
inline fun sendMessageRequestOf(
    queueUrl: String,
    messageBody: String,
    delaySeconds: Int? = null,
    builder: SendMessageRequest.Builder.() -> Unit = {},
): SendMessageRequest {
    queueUrl.requireNotBlank("queueUrl")
    messageBody.requireNotBlank("messageBody")
    delaySeconds?.validateSqsDelaySeconds("delaySeconds")

    return sendMessageRequest {
        queueUrl(queueUrl)
        messageBody(messageBody)
        delaySeconds?.let { delaySeconds(it) }
        builder()
    }.also { request ->
        request.queueUrl().requireNotBlank("queueUrl")
        request.messageBody().requireNotBlank("messageBody")
        request.delaySeconds()?.validateSqsDelaySeconds("delaySeconds")
    }
}

/**
 * [SendMessageBatchRequestEntry] 를 생성합니다.
 *
 * @param builder [SendMessageBatchRequestEntry.Builder]를 이용하여 [SendMessageBatchRequestEntry]를 초기화하는 람다입니다.
 *
 * ```kotlin
 * val entry = sendMessageBatchRequestEntry {
 *     id("msg-1")
 *     messageBody("hello")
 * }
 * // entry.id() == "msg-1"
 * ```
 */
inline fun sendMessageBatchRequestEntry(
    builder: SendMessageBatchRequestEntry.Builder.() -> Unit,
): SendMessageBatchRequestEntry {
    return SendMessageBatchRequestEntry.builder().apply(builder).build()
}

/**
 * [SendMessageBatchRequestEntry]를 구성합니다.
 *
 * @param id                이 배치에서 메시지를 식별하는 값
 * @param messageGroupId    이 배치의 메시지 그룹 식별자
 * @param messageBody       전송할 메시지
 * @param delaySeconds      특정 메시지 전송을 지연할 시간(초). 범위: 0..900
 * @param builder       빌더를 초기화하는 람다
 * @receiver            요청을 구성할 빌더
 * @return            [SendMessageBatchRequestEntry] 인스턴스
 */
inline fun sendMessageBatchRequestEntryOf(
    id: String,
    messageGroupId: String,
    messageBody: String,
    delaySeconds: Int? = null,
    builder: SendMessageBatchRequestEntry.Builder.() -> Unit = {},
): SendMessageBatchRequestEntry {
    id.requireNotBlank("id")
    messageGroupId.requireNotBlank("messageGroupId")
    messageBody.requireNotBlank("messageBody")
    delaySeconds?.validateSqsDelaySeconds("delaySeconds")

    return sendMessageBatchRequestEntry {
        id(id)
        messageGroupId(messageGroupId)
        messageBody(messageBody)
        delaySeconds?.let { delaySeconds(it) }

        builder()
    }
}
