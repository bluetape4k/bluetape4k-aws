package io.bluetape4k.aws.spring.sns

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.model.PublishBatchRequest
import software.amazon.awssdk.services.sns.model.PublishBatchResponse
import java.util.concurrent.CompletableFuture

private const val MAX_SNS_BATCH_ENTRIES: Int = 10

/**
 * 한 template invocation의 SNS client 접근을 제한하는 내부 port 구현입니다.
 *
 * guard는 request ID subset, duplicate claim, 동시 chunk 수, lifecycle을 한 곳에서 검증합니다.
 * 주입된 [SnsAsyncClient]는 caller/Spring bean이 소유하며 template은 이 client를 닫지 않습니다.
 * strategy 작업은 호출자의 structured coroutine scope 안에서만 실행되어야 합니다.
 */
internal class SnsBatchExecutionGuard(
    private val snsAsyncClient: SnsAsyncClient,
    request: SnsPublishBatchRequest,
    private val options: SnsBatchExecutionOptions,
) : SnsBatchExecutionPort {

    private val requestEntryIds = request.entries.mapTo(mutableSetOf()) { it.id }
    private val topicArn = request.topicArn
    private val mutex = Mutex()
    private val activeEntryIds = mutableSetOf<String>()
    private val attemptedEntryIds = mutableSetOf<String>()
    private val completedEntryIds = mutableListOf<String>()
    private val inFlightFutures = mutableSetOf<CompletableFuture<PublishBatchResponse>>()
    private var activeChunkCount: Int = 0
    private var lifecycle: Lifecycle = Lifecycle.OPEN

    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    override suspend fun publishChunk(entries: List<SnsPublishBatchEntry>): SnsPublishBatchResult {
        val chunk = claim(entries)
        var future: CompletableFuture<PublishBatchResponse>? = null
        try {
            val sdkRequest = PublishBatchRequest.builder()
                .topicArn(topicArn)
                .publishBatchRequestEntries(chunk.map(::toSdkEntry))
                .build()
            future = snsAsyncClient.publishBatch(sdkRequest)
            mutex.withLock {
                if (lifecycle != Lifecycle.OPEN) {
                    future?.cancel(true)
                    throw contract(SnsBatchExecutionContractError.PORT_CLOSED)
                }
                inFlightFutures += future!!
            }
            val response = future!!.await()
            withContext(NonCancellable) {
                recordCompleted(chunk.map { it.id })
            }
            val mapped = SnsBatchResponseMapper.map(chunk, response)
            return SnsPublishBatchResult(mapped.successful, mapped.failed)
        } catch (cause: CancellationException) {
            future?.cancel(true)
            throw cause
        } catch (cause: SnsBatchExecutionContractException) {
            throw cause
        } catch (cause: SnsBatchTransportException) {
            throw cause
        } catch (cause: SnsBatchProtocolException) {
            throw cause
        } catch (cause: RuntimeException) {
            val completed = completedSnapshot()
            throw SnsBatchTransportException.from(cause, completed)
        } finally {
            withContext(NonCancellable) {
                release(chunk.map { it.id }, future)
            }
        }
    }

    /** aggregate result의 ID 집합을 최종 검증합니다. */
    internal fun validateAggregate(result: SnsPublishBatchResult, request: SnsPublishBatchRequest) {
        SnsBatchResponseMapper.map(request.entries, result)
    }

    /**
     * 호출자가 취소한 뒤에도 전체 drain을 완료하고 guard를 CLOSED로 전환합니다.
     * active claim이 남으면 caller가 불확실한 상태를 재사용하지 못하도록 계약 예외를 발생시킵니다.
     */
    internal suspend fun closeAndDrain() = withContext(NonCancellable) {
        mutex.withLock {
            when (lifecycle) {
                Lifecycle.OPEN -> lifecycle = Lifecycle.CLOSING
                Lifecycle.CLOSING -> Unit
                Lifecycle.CLOSED -> return@withContext
            }
        }

        while (true) {
            val futures = mutex.withLock { inFlightFutures.toList() }
            futures.forEach { it.cancel(true) }
            val drained = mutex.withLock {
                activeChunkCount == 0 && inFlightFutures.isEmpty()
            }
            if (drained) {
                break
            }
            yield()
        }

        mutex.withLock {
            if (activeEntryIds.isNotEmpty() || activeChunkCount != 0) {
                throw contract(SnsBatchExecutionContractError.OUTSTANDING_CLAIM)
            }
            lifecycle = Lifecycle.CLOSED
        }
    }

    @Suppress("ThrowsCount")
    private suspend fun claim(entries: List<SnsPublishBatchEntry>): List<SnsPublishBatchEntry> {
        val chunk = mutex.withLock {
            when (lifecycle) {
                Lifecycle.OPEN -> Unit
                Lifecycle.CLOSING, Lifecycle.CLOSED ->
                    throw contract(SnsBatchExecutionContractError.PORT_CLOSED)
            }
            if (entries.size !in 1..MAX_SNS_BATCH_ENTRIES) {
                throw contract(SnsBatchExecutionContractError.INVALID_CHUNK)
            }
            val copy = entries.toList()
            val ids = copy.map { it.id }
            if (ids.size != ids.toSet().size || ids.any { it in attemptedEntryIds }) {
                throw contract(SnsBatchExecutionContractError.DUPLICATE_CLAIM)
            }
            if (ids.any { it !in requestEntryIds }) {
                throw contract(SnsBatchExecutionContractError.INVALID_RESULT)
            }
            if (activeChunkCount >= options.maxInFlightBatches) {
                throw contract(SnsBatchExecutionContractError.TOO_MANY_IN_FLIGHT)
            }
            attemptedEntryIds += ids
            activeEntryIds += ids
            activeChunkCount++
            copy
        }
        return chunk
    }

    private suspend fun recordCompleted(ids: List<String>) {
        mutex.withLock { completedEntryIds += ids }
    }

    private suspend fun completedSnapshot(): List<String> = mutex.withLock { completedEntryIds.toList() }

    private suspend fun release(
        ids: List<String>,
        future: CompletableFuture<PublishBatchResponse>?,
    ) {
        mutex.withLock {
            activeEntryIds.removeAll(ids.toSet())
            if (activeChunkCount > 0) {
                activeChunkCount--
            }
            future?.let(inFlightFutures::remove)
        }
    }

    private fun toSdkEntry(entry: SnsPublishBatchEntry) =
        software.amazon.awssdk.services.sns.model.PublishBatchRequestEntry.builder()
            .id(entry.id)
            .message(entry.message)
            .apply {
                entry.subject?.let(::subject)
                if (entry.messageAttributes.isNotEmpty()) {
                    messageAttributes(entry.messageAttributes)
                }
                entry.messageGroupId?.let(::messageGroupId)
                entry.messageDeduplicationId?.let(::messageDeduplicationId)
            }
            .build()

    private fun contract(error: SnsBatchExecutionContractError) =
        SnsBatchExecutionContractException(error)

    private enum class Lifecycle {
        OPEN,
        CLOSING,
        CLOSED,
    }
}
