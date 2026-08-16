package io.bluetape4k.aws.spring.sqs

import software.amazon.awssdk.services.sqs.SqsAsyncClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

/** 자동 배치 coordinator가 사용하는 internal 전송 경계입니다. */
internal interface SqsBatchTransport {
    fun send(entry: SqsBatchSendEntry): CompletableFuture<SqsBatchOutcome>

    fun delete(entry: SqsBatchDeleteEntry): CompletableFuture<SqsBatchOutcome>
}

/** caller가 소유한 [SqsAsyncClient]로 단건 요청을 전송하는 transport입니다. */
internal class DirectSqsBatchTransport(
    private val client: SqsAsyncClient,
) : SqsBatchTransport {

    override fun send(entry: SqsBatchSendEntry): CompletableFuture<SqsBatchOutcome> {
        val request = entry.request
        return submitBatchOutcome(
            entryId = entry.entryId,
            submit = {
                client.sendMessage {
                    it.queueUrl(request.queueUrl)
                    it.messageBody(request.body)
                    request.delaySeconds?.let(it::delaySeconds)
                    request.messageGroupId?.let(it::messageGroupId)
                    request.messageDeduplicationId?.let(it::messageDeduplicationId)
                    if (request.messageAttributes.isNotEmpty()) {
                        it.messageAttributes(request.messageAttributes)
                    }
                }
            },
            success = { response ->
                SqsBatchOutcome.SendSuccess(
                    entryId = entry.entryId,
                    messageId = response.messageId(),
                    sequenceNumber = response.sequenceNumber(),
                )
            }
        )
    }

    override fun delete(entry: SqsBatchDeleteEntry): CompletableFuture<SqsBatchOutcome> =
        submitBatchOutcome(
            entryId = entry.entryId,
            submit = {
                client.deleteMessage {
                    it.queueUrl(entry.queueUrl)
                    it.receiptHandle(entry.receiptHandle)
                }
            },
            success = { SqsBatchOutcome.DeleteSuccess(entry.entryId) },
        )
}

@Suppress("TooGenericExceptionCaught")
private fun <T: Any> submitBatchOutcome(
    entryId: String,
    submit: () -> CompletableFuture<T>,
    success: (T) -> SqsBatchOutcome,
): CompletableFuture<SqsBatchOutcome> =
    try {
        submit().mapBatchOutcome(entryId, success)
    } catch (cause: Exception) {
        CompletableFuture.completedFuture(SqsBatchOutcome.Failure(normalizeBatchFailure(entryId, cause)))
    }

@Suppress("TooGenericExceptionCaught")
private fun <T: Any> CompletableFuture<T>.mapBatchOutcome(
    entryId: String,
    success: (T) -> SqsBatchOutcome,
): CompletableFuture<SqsBatchOutcome> {
    val mapped = SqsBatchOutcomeFuture(this)
    whenComplete { response, failure ->
        try {
            if (failure != null) {
                mapped.complete(SqsBatchOutcome.Failure(normalizeBatchFailure(entryId, failure)))
            } else {
                mapped.complete(success(requireNotNull(response)))
            }
        } catch (cause: Throwable) {
            mapped.completeExceptionally(cause)
        } finally {
            mapped.releaseSource()
        }
    }
    return mapped
}

private class SqsBatchOutcomeFuture(
    source: CompletableFuture<*>,
) : CompletableFuture<SqsBatchOutcome>() {
    private val sourceReference = AtomicReference<CompletableFuture<*>?>(source)

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        val cancelled = super.cancel(false)
        if (cancelled) {
            sourceReference.getAndSet(null)?.cancel(false)
        }
        return cancelled
    }

    fun releaseSource() {
        sourceReference.set(null)
    }
}
