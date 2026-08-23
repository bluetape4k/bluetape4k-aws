package io.bluetape4k.aws.lambda

import kotlinx.coroutines.future.await
import software.amazon.awssdk.services.lambda.LambdaAsyncClient
import software.amazon.awssdk.services.lambda.model.InvocationType
import software.amazon.awssdk.services.lambda.model.InvokeRequest
import software.amazon.awssdk.services.lambda.model.LogType

/** 비동기 바이트 호출을 기다리는 coroutine helper입니다. */
suspend fun LambdaAsyncClient.invokeBytes(
    functionName: String,
    payload: ByteArray? = null,
    qualifier: String? = null,
    invocationType: InvocationType = InvocationType.REQUEST_RESPONSE,
    logType: LogType = LogType.NONE,
    builder: InvokeRequest.Builder.() -> Unit = {},
): LambdaInvocationResult<ByteArray> = invokeBytesAsync(
    functionName,
    payload,
    qualifier,
    invocationType,
    logType,
    builder,
).await()

/** 비동기 UTF-8 문자열 호출을 기다리는 coroutine helper입니다. */
suspend fun LambdaAsyncClient.invokeString(
    functionName: String,
    payload: String? = null,
    qualifier: String? = null,
    invocationType: InvocationType = InvocationType.REQUEST_RESPONSE,
    logType: LogType = LogType.NONE,
    builder: InvokeRequest.Builder.() -> Unit = {},
): LambdaInvocationResult<String> = invokeStringAsync(
    functionName,
    payload,
    qualifier,
    invocationType,
    logType,
    builder,
).await()

/** typed 비동기 호출을 기다리는 coroutine helper입니다. */
suspend fun <T> LambdaAsyncClient.invokeTyped(
    functionName: String,
    payload: T,
    codec: LambdaPayloadCodec<T>,
    qualifier: String? = null,
    invocationType: InvocationType = InvocationType.REQUEST_RESPONSE,
    logType: LogType = LogType.NONE,
    builder: InvokeRequest.Builder.() -> Unit = {},
): LambdaInvocationResult<T> = invokeTypedAsync(
    functionName,
    payload,
    codec,
    qualifier,
    invocationType,
    logType,
    builder,
).await()
