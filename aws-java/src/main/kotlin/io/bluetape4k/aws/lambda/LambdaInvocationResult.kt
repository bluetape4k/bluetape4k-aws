package io.bluetape4k.aws.lambda

import software.amazon.awssdk.services.lambda.model.InvokeResponse
import java.util.Base64

/**
 * Lambda invoke의 raw response와 codec으로 변환한 결과를 함께 보존합니다.
 *
 * 함수 내부 오류는 transport 예외가 아니므로 이 결과의 [hasFunctionError]로
 * 구분합니다. 호출자는 필요할 때 [response]에서 SDK가 추가한 원본 필드를 읽을 수
 * 있습니다.
 */
data class LambdaInvocationResult<T>(
    /** AWS SDK가 반환한 원본 response입니다. */
    val response: InvokeResponse,
    /** payload를 codec으로 디코딩한 값입니다. payload가 없으면 null입니다. */
    val value: T?,
    /** response에서 복사한 raw payload입니다. 빈 payload와 null은 구분됩니다. */
    val payload: ByteArray?,
    /** base64 log tail을 UTF-8로 디코딩한 값입니다. */
    val logTail: String?,
) {

    /** Lambda service가 반환한 HTTP status code입니다. */
    val statusCode: Int?
        get() = response.statusCode()

    /** 함수 실행 오류의 원본 문자열입니다. */
    val functionError: String?
        get() = response.functionError()

    /** 함수 실행 오류 문자열이 blank가 아닌지 나타냅니다. */
    val hasFunctionError: Boolean
        get() = !functionError.isNullOrBlank()
}

/** SDK response를 codec 기반 [LambdaInvocationResult]로 변환합니다. */
fun <T> InvokeResponse.toLambdaInvocationResult(codec: LambdaPayloadCodec<T>): LambdaInvocationResult<T> {
    val copiedPayload = payload()?.asByteArrayUnsafe()?.copyOf()
    val decodedValue = copiedPayload?.let(codec::decode)
    val decodedLogTail = logResult()?.let { Base64.getDecoder().decode(it).toString(Charsets.UTF_8) }

    return LambdaInvocationResult(
        response = this,
        value = decodedValue,
        payload = copiedPayload,
        logTail = decodedLogTail,
    )
}
