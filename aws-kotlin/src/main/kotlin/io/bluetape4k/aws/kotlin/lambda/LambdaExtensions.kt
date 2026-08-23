package io.bluetape4k.aws.kotlin.lambda

import aws.sdk.kotlin.services.lambda.LambdaClient
import aws.sdk.kotlin.services.lambda.model.InvocationType
import aws.sdk.kotlin.services.lambda.model.InvokeRequest
import aws.sdk.kotlin.services.lambda.model.LogType
import io.bluetape4k.aws.kotlin.lambda.model.invokeRequestOf

/** 원시 바이트 payload로 Lambda를 suspend 호출합니다. */
suspend fun LambdaClient.invokeBytes(
    functionName: String,
    payload: ByteArray? = null,
    qualifier: String? = null,
    invocationType: InvocationType = InvocationType.RequestResponse,
    logType: LogType = LogType.None,
    builder: InvokeRequest.Builder.() -> Unit = {},
): LambdaInvocationResult<ByteArray> = invoke(
    invokeRequestOf(functionName, payload, qualifier, invocationType, logType, builder),
).toLambdaInvocationResult(LambdaPayloadCodecs.bytes)

/** UTF-8 문자열 payload로 Lambda를 suspend 호출합니다. */
suspend fun LambdaClient.invokeString(
    functionName: String,
    payload: String? = null,
    qualifier: String? = null,
    invocationType: InvocationType = InvocationType.RequestResponse,
    logType: LogType = LogType.None,
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

/** caller가 제공한 codec으로 typed payload를 suspend 호출합니다. */
suspend fun <T> LambdaClient.invokeTyped(
    functionName: String,
    payload: T,
    codec: LambdaPayloadCodec<T>,
    qualifier: String? = null,
    invocationType: InvocationType = InvocationType.RequestResponse,
    logType: LogType = LogType.None,
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
