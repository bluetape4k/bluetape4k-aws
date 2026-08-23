package io.bluetape4k.aws.lambda

import io.bluetape4k.aws.lambda.model.invokeRequestOf
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.InvocationType
import software.amazon.awssdk.services.lambda.model.InvokeRequest
import software.amazon.awssdk.services.lambda.model.LogType

/** 원시 바이트 payload로 Lambda를 동기 호출합니다. */
fun LambdaClient.invokeBytes(
    functionName: String,
    payload: ByteArray? = null,
    qualifier: String? = null,
    invocationType: InvocationType = InvocationType.REQUEST_RESPONSE,
    logType: LogType = LogType.NONE,
    builder: InvokeRequest.Builder.() -> Unit = {},
): LambdaInvocationResult<ByteArray> = invoke(
    invokeRequestOf(functionName, payload, qualifier, invocationType, logType, builder),
).toLambdaInvocationResult(LambdaPayloadCodecs.bytes)

/** UTF-8 문자열 payload로 Lambda를 동기 호출합니다. */
fun LambdaClient.invokeString(
    functionName: String,
    payload: String? = null,
    qualifier: String? = null,
    invocationType: InvocationType = InvocationType.REQUEST_RESPONSE,
    logType: LogType = LogType.NONE,
    builder: InvokeRequest.Builder.() -> Unit = {},
): LambdaInvocationResult<String> = invoke(
    invokeRequestOf(
        functionName = functionName,
        payload = payload?.let(LambdaPayloadCodecs.utf8::encode),
        qualifier = qualifier,
        invocationType = invocationType,
        logType = logType,
        builder = builder,
    ),
).toLambdaInvocationResult(LambdaPayloadCodecs.utf8)

/** caller가 제공한 codec으로 typed payload를 동기 호출합니다. */
fun <T> LambdaClient.invokeTyped(
    functionName: String,
    payload: T,
    codec: LambdaPayloadCodec<T>,
    qualifier: String? = null,
    invocationType: InvocationType = InvocationType.REQUEST_RESPONSE,
    logType: LogType = LogType.NONE,
    builder: InvokeRequest.Builder.() -> Unit = {},
): LambdaInvocationResult<T> = invoke(
    invokeRequestOf(
        functionName = functionName,
        payload = codec.encode(payload),
        qualifier = qualifier,
        invocationType = invocationType,
        logType = logType,
        builder = builder,
    ),
).toLambdaInvocationResult(codec)
