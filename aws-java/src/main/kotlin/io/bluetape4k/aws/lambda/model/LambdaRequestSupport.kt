package io.bluetape4k.aws.lambda.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.lambda.model.InvocationType
import software.amazon.awssdk.services.lambda.model.InvokeRequest
import software.amazon.awssdk.services.lambda.model.LogType

/**
 * AWS Lambda invoke 요청을 구성합니다.
 *
 * callback을 적용한 뒤 최종 request를 검증하며 payload가 null이면 SDK request에서
 * 생략하고 빈 배열은 유효한 빈 payload로 보존합니다. ARN 형식, payload 크기, IAM,
 * 함수 존재 여부는 AWS service 계약에 위임합니다.
 */
inline fun invokeRequestOf(
    functionName: String,
    payload: ByteArray? = null,
    qualifier: String? = null,
    invocationType: InvocationType = InvocationType.REQUEST_RESPONSE,
    logType: LogType = LogType.NONE,
    builder: InvokeRequest.Builder.() -> Unit = {},
): InvokeRequest = InvokeRequest.builder()
    .functionName(functionName)
    .also { payload?.let { value -> it.payload(SdkBytes.fromByteArray(value)) } }
    .also { qualifier?.let(it::qualifier) }
    .invocationType(invocationType)
    .logType(logType)
    .apply(builder)
    .build()
    .also { it.validate() }

@PublishedApi
internal fun InvokeRequest.validate() {
    functionName().requireNotBlank("functionName")
    qualifier()?.requireNotBlank("qualifier")
    require(logType() != LogType.TAIL || invocationType() == InvocationType.REQUEST_RESPONSE) {
        "logType TAIL requires invocationType REQUEST_RESPONSE"
    }
}
