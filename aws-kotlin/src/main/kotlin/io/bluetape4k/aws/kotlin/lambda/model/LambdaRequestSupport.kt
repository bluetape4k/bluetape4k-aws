package io.bluetape4k.aws.kotlin.lambda.model

import aws.sdk.kotlin.services.lambda.model.InvocationType
import aws.sdk.kotlin.services.lambda.model.InvokeRequest
import aws.sdk.kotlin.services.lambda.model.LogType
import io.bluetape4k.support.requireNotBlank

/**
 * AWS Kotlin SDK Lambda invoke 요청을 구성합니다.
 *
 * callback을 적용한 뒤 최종 request를 검증하며 payload가 null이면 생략하고 빈 배열은
 * 그대로 보존합니다. ARN 형식, payload 크기, IAM, 함수 존재 여부는 AWS 계약에 위임합니다.
 */
inline fun invokeRequestOf(
    functionName: String,
    payload: ByteArray? = null,
    qualifier: String? = null,
    invocationType: InvocationType = InvocationType.RequestResponse,
    logType: LogType = LogType.None,
    crossinline builder: InvokeRequest.Builder.() -> Unit = {},
): InvokeRequest = InvokeRequest {
    this.functionName = functionName
    payload?.let { this.payload = it.copyOf() }
    this.qualifier = qualifier
    this.invocationType = invocationType
    this.logType = logType
    builder()
}.also(::validateInvokeRequest)

@PublishedApi
internal fun validateInvokeRequest(request: InvokeRequest) {
    request.functionName?.requireNotBlank("functionName")
        ?: throw IllegalArgumentException("functionName is required")
    request.qualifier?.requireNotBlank("qualifier")
    require(request.logType != LogType.Tail || request.invocationType == InvocationType.RequestResponse) {
        "logType Tail requires invocationType RequestResponse"
    }
}
