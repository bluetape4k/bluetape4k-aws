package io.bluetape4k.aws.coroutines

import io.bluetape4k.coroutines.support.getOrCurrent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Wraps an argument-free synchronous AWS call as a `Dispatchers.IO`-based suspend call.
 *
 * ## Behavior and contract
 * - Runs [method] in the `context.getOrCurrent() + Dispatchers.IO` context.
 * - When [context] is `EmptyCoroutineContext`, adds the IO dispatcher to the current context.
 *
 * ```kotlin
 * suspend fun loadBuckets(s3: software.amazon.awssdk.services.s3.S3Client): Int =
 *     suspendCommand { s3.listBuckets().buckets().size }
 * // result == bucket count (Int)
 * ```
 */
suspend inline fun <RES: Any> suspendCommand(
    context: CoroutineContext = EmptyCoroutineContext,
    crossinline method: () -> RES,
): RES = withContext(context.getOrCurrent() + Dispatchers.IO) {
    method()
}

/**
 * Wraps a synchronous AWS call that accepts a request object as a `Dispatchers.IO`-based suspend call.
 *
 * ## Behavior and contract
 * - Runs [method] with [request] in `context.getOrCurrent() + Dispatchers.IO`.
 * - Exceptions thrown by [method] are propagated to the caller without being hidden.
 *
 * ```kotlin
 * suspend fun loadObject(
 *     s3: software.amazon.awssdk.services.s3.S3Client,
 *     request: software.amazon.awssdk.services.s3.model.GetObjectRequest,
 * ) = suspendCommand(request = request, method = s3::getObjectAsBytes)
 * // result == ResponseBytes<GetObjectResponse>
 * ```
 */
suspend inline fun <REQ, RES: Any> suspendCommand(
    context: CoroutineContext = EmptyCoroutineContext,
    request: REQ,
    crossinline method: (request: REQ) -> RES,
): RES = withContext(context.getOrCurrent() + Dispatchers.IO) {
    method(request)
}
