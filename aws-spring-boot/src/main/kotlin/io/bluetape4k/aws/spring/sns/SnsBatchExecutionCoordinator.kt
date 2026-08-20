package io.bluetape4k.aws.spring.sns

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val SNS_BATCH_SIZE: Int = 10

/** AWS/strategy batch 결과를 bounded worker와 입력 순서 결과로 조립하는 내부 coordinator입니다. */
internal class SnsBatchExecutionCoordinator<T>(
    private val publishChunk: suspend (topicArn: String, entries: List<SnsPublishBatchEntry>) -> T,
    private val mapChunk: (entries: List<SnsPublishBatchEntry>, response: T) -> SnsBatchChunkResult,
    private val onCompletedEntryIds: (List<String>) -> Unit = {},
) {

    /**
     * 입력을 10개씩 나누고 고정 worker만 실행합니다.
     *
     * 각 worker는 ordered collector가 해당 결과를 소비할 때까지 다음 chunk를 claim하지 않으므로
     * 첫 sequence가 지연되어도 pending result와 resident entry가 worker 수를 넘지 않습니다.
     */
    @Suppress("CyclomaticComplexMethod", "ThrowsCount", "TooGenericExceptionCaught")
    suspend fun execute(
        request: SnsPublishBatchRequest,
        options: SnsBatchExecutionOptions = SnsBatchExecutionOptions(),
    ): SnsPublishBatchResult {
        require(options.maxInFlightBatches > 0) { "maxInFlightBatches must be positive." }
        if (request.entries.isEmpty()) {
            return SnsPublishBatchResult(emptyList(), emptyList())
        }

        val chunks = request.entries.chunked(SNS_BATCH_SIZE)
        val workerCount = minOf(options.maxInFlightBatches, chunks.size)
        val nextChunk = AtomicInteger(0)
        val completedMutex = Mutex()
        val completedEntryIds = mutableListOf<String>()
        val cancellationCause = AtomicReference<CancellationException?>()
        val resultChannel = Channel<ChunkResult>(capacity = 0)

        suspend fun recordCompleted(ids: List<String>) {
            completedMutex.withLock { completedEntryIds += ids }
            onCompletedEntryIds(ids)
        }

        return try {
            coroutineScope {
                suspend fun runWorker() {
                    try {
                        while (true) {
                            val sequence = nextChunk.getAndIncrement()
                            val chunk = chunks.getOrNull(sequence) ?: break
                            val response = publishChunk(request.topicArn, chunk)
                            withContext(NonCancellable) {
                                recordCompleted(chunk.map { it.id })
                            }
                            val result = mapChunk(chunk, response)
                            val acknowledgement = CompletableDeferred<Unit>()
                            resultChannel.send(ChunkResult(sequence, result, acknowledgement))
                            acknowledgement.await()
                        }
                    } catch (cause: CancellationException) {
                        cancellationCause.compareAndSet(null, cause)
                        throw cause
                    }
                }

                val workers = (0 until workerCount).map { async { runWorker() } }
                val ordered = async {
                    collectResults(resultChannel, chunks.size)
                }
                try {
                    workers.awaitAll()
                    resultChannel.close()
                    ordered.await()
                } finally {
                    resultChannel.cancel()
                    workers.forEach { it.cancel() }
                }
            }
        } catch (cause: CancellationException) {
            throw cancellationCause.get() ?: cause
        } catch (cause: SnsBatchTransportException) {
            throw cause
        } catch (cause: SnsBatchProtocolException) {
            throw cause
        } catch (cause: SnsBatchExecutionContractException) {
            throw cause
        } catch (cause: RuntimeException) {
            val completed = completedMutex.withLock { completedEntryIds.toList() }
            throw SnsBatchTransportException.from(cause, completed)
        }
    }

    private suspend fun collectResults(
        resultChannel: Channel<ChunkResult>,
        chunkCount: Int,
    ): SnsPublishBatchResult {
        val pending = sortedMapOf<Int, ChunkResult>()
        val successful = mutableListOf<SnsPublishBatchSuccess>()
        val failed = mutableListOf<SnsPublishBatchFailure>()
        var nextSequence = 0

        repeat(chunkCount) {
            val chunk = resultChannel.receive()
            if (pending.put(chunk.sequence, chunk) != null) {
                throw SnsBatchExecutionContractException(SnsBatchExecutionContractError.INVALID_RESULT)
            }
            while (true) {
                val ordered = pending.remove(nextSequence) ?: break
                successful += ordered.result.successful
                failed += ordered.result.failed
                ordered.acknowledgement.complete(Unit)
                nextSequence++
            }
        }
        return SnsPublishBatchResult(successful, failed)
    }

    private data class ChunkResult(
        val sequence: Int,
        val result: SnsBatchChunkResult,
        val acknowledgement: CompletableDeferred<Unit>,
    )
}

/** 입력 순서로 합쳐지기 전 한 chunk의 typed 결과입니다. */
internal data class SnsBatchChunkResult(
    val successful: List<SnsPublishBatchSuccess>,
    val failed: List<SnsPublishBatchFailure>,
)
