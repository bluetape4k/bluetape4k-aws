package io.bluetape4k.aws.kotlin.s3.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** 생성에 성공한 버킷만 역순으로 정리하고, 원래 실패와 취소를 보존합니다. */
internal suspend fun <T> withTestBuckets(
    buckets: List<String>,
    create: suspend (String) -> Unit,
    delete: suspend (String) -> Unit,
    block: suspend () -> T,
): T {
    val created = mutableListOf<String>()
    val result = try {
        buckets.forEach {
            create(it)
            created.add(it)
        }
        Result.success(block())
    } catch (cancelled: CancellationException) {
        // 정리가 끝난 직후 같은 취소를 다시 전달합니다.
        Result.failure(cancelled)
    } catch (error: Throwable) {
        Result.failure(error)
    }
    var failure = result.exceptionOrNull()
    withContext(NonCancellable) {
        created.asReversed().forEach { bucket ->
            try {
                delete(bucket)
            } catch (error: Throwable) {
                // 정리 실패도 보존하되 다른 버킷의 정리를 끝까지 시도합니다.
                val primary = failure
                if (primary == null) failure = error
                else if (primary !== error) primary.addSuppressed(error)
            }
        }
    }
    failure?.let { throw it }
    return result.getOrThrow()
}
