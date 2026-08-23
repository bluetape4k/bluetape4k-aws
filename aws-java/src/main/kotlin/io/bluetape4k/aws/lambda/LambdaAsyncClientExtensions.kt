package io.bluetape4k.aws.lambda

import io.bluetape4k.aws.lambda.model.invokeRequestOf
import software.amazon.awssdk.services.lambda.LambdaAsyncClient
import software.amazon.awssdk.services.lambda.model.InvocationType
import software.amazon.awssdk.services.lambda.model.InvokeRequest
import software.amazon.awssdk.services.lambda.model.InvokeResponse
import software.amazon.awssdk.services.lambda.model.LogType
import java.util.concurrent.CompletableFuture

/** 원시 바이트 payload로 Lambda를 비동기 호출합니다. */
fun LambdaAsyncClient.invokeBytesAsync(
    functionName: String,
    payload: ByteArray? = null,
    qualifier: String? = null,
    invocationType: InvocationType = InvocationType.REQUEST_RESPONSE,
    logType: LogType = LogType.NONE,
    builder: InvokeRequest.Builder.() -> Unit = {},
): CompletableFuture<LambdaInvocationResult<ByteArray>> = invokeAsync(
    invokeRequestOf(functionName, payload, qualifier, invocationType, logType, builder),
    LambdaPayloadCodecs.bytes,
)

/** UTF-8 문자열 payload로 Lambda를 비동기 호출합니다. */
fun LambdaAsyncClient.invokeStringAsync(
    functionName: String,
    payload: String? = null,
    qualifier: String? = null,
    invocationType: InvocationType = InvocationType.REQUEST_RESPONSE,
    logType: LogType = LogType.NONE,
    builder: InvokeRequest.Builder.() -> Unit = {},
): CompletableFuture<LambdaInvocationResult<String>> = invokeAsync(
    invokeRequestOf(
        functionName = functionName,
        payload = payload?.let(LambdaPayloadCodecs.utf8::encode),
        qualifier = qualifier,
        invocationType = invocationType,
        logType = logType,
        builder = builder,
    ),
    LambdaPayloadCodecs.utf8,
)

/** caller가 제공한 codec으로 typed payload를 비동기 호출합니다. */
fun <T> LambdaAsyncClient.invokeTypedAsync(
    functionName: String,
    payload: T,
    codec: LambdaPayloadCodec<T>,
    qualifier: String? = null,
    invocationType: InvocationType = InvocationType.REQUEST_RESPONSE,
    logType: LogType = LogType.NONE,
    builder: InvokeRequest.Builder.() -> Unit = {},
): CompletableFuture<LambdaInvocationResult<T>> = invokeAsync(
    invokeRequestOf(
        functionName = functionName,
        payload = codec.encode(payload),
        qualifier = qualifier,
        invocationType = invocationType,
        logType = logType,
        builder = builder,
    ),
    codec,
)

private fun <T> LambdaAsyncClient.invokeAsync(
    request: InvokeRequest,
    codec: LambdaPayloadCodec<T>,
): CompletableFuture<LambdaInvocationResult<T>> {
    val sdkFuture = invoke(request)
    val resultFuture = CompletableFuture<LambdaInvocationResult<T>>()

    sdkFuture.whenComplete { response: InvokeResponse?, error: Throwable? ->
        if (resultFuture.isCancelled) return@whenComplete
        if (error != null) {
            resultFuture.completeExceptionally(error)
        } else if (response != null && !resultFuture.isCancelled) {
            runCatching { response.toLambdaInvocationResult(codec) }
                .onSuccess(resultFuture::complete)
                .onFailure(resultFuture::completeExceptionally)
        } else if (!resultFuture.isDone) {
            resultFuture.completeExceptionally(NullPointerException("Lambda SDK returned a null response"))
        }
    }

    resultFuture.whenComplete { _, _ ->
        if (resultFuture.isCancelled) {
            sdkFuture.cancel(true)
        }
    }
    return resultFuture
}
