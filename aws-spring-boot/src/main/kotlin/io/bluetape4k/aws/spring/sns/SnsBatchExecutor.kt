package io.bluetape4k.aws.spring.sns

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import software.amazon.awssdk.services.sns.model.PublishBatchResponse
import java.util.concurrent.atomic.AtomicReference

private const val SNS_BATCH_SIZE: Int = 10

/** AWS PublishBatch 호출을 bounded worker와 입력 순서 결과로 조립하는 내부 실행기입니다. */
internal class SnsBatchExecutor(
    private val publishChunk: suspend (
        topicArn: String,
        entries: List<SnsPublishBatchEntry>,
    ) -> PublishBatchResponse,
) {

    @Suppress("CyclomaticComplexMethod", "ThrowsCount", "TooGenericExceptionCaught")
    suspend fun execute(
        request: SnsPublishBatchRequest,
        options: SnsBatchExecutionOptions = SnsBatchExecutionOptions(),
    ): SnsPublishBatchResult {
        require(options.maxInFlightBatches > 0) { "maxInFlightBatches must be positive." }
        if (request.entries.isEmpty()) {
            return SnsPublishBatchResult(emptyList(), emptyList())
        }

        val chunkCount = (request.entries.size + SNS_BATCH_SIZE - 1) / SNS_BATCH_SIZE
        val workerCount = minOf(options.maxInFlightBatches, chunkCount)
        val iterator = request.entries.iterator()
        val claimMutex = Mutex()
        val completedMutex = Mutex()
        val completedEntryIds = mutableListOf<String>()
        val cancellationCause = AtomicReference<CancellationException?>()
        val inFlight = Semaphore(workerCount)
        val resultChannel = Channel<ChunkResult>(capacity = 0)
        var nextSequence = 0

        suspend fun claimChunk(): Chunk? = claimMutex.withLock {
            if (!iterator.hasNext()) {
                return@withLock null
            }
            val entries = buildList(SNS_BATCH_SIZE) {
                while (iterator.hasNext() && size < SNS_BATCH_SIZE) {
                    add(iterator.next())
                }
            }
            Chunk(sequence = nextSequence++, entries = entries)
        }

        suspend fun recordCompleted(ids: List<String>) {
            completedMutex.withLock { completedEntryIds += ids }
        }

        return try {
            suspend fun runWorker() {
                try {
                    while (true) {
                        inFlight.acquire()
                        val chunk = claimChunk()
                        if (chunk == null) {
                            inFlight.release()
                            break
                        }
                        val response = publishChunk(request.topicArn, chunk.entries)
                        withContext(NonCancellable) {
                            recordCompleted(chunk.entries.map { it.id })
                        }
                        resultChannel.send(ChunkResult(chunk.sequence, mapResponse(chunk.entries, response)))
                    }
                } catch (cause: CancellationException) {
                    cancellationCause.compareAndSet(null, cause)
                    throw cause
                }
            }

            coroutineScope {
                val workers = (0 until workerCount).map { async { runWorker() } }
                val ordered = async { collectResults(resultChannel, chunkCount, inFlight) }
                try {
                    workers.awaitAll()
                    resultChannel.close()
                    ordered.await()
                } finally {
                    resultChannel.cancel()
                }
            }
        } catch (cause: CancellationException) {
            throw cancellationCause.get() ?: cause
        } catch (cause: SnsBatchTransportException) {
            throw cause
        } catch (cause: SnsBatchProtocolException) {
            throw cause
        } catch (cause: RuntimeException) {
            val completed = completedMutex.withLock { completedEntryIds.toList() }
            throw SnsBatchTransportException.from(cause, completed)
        }
    }

    private suspend fun collectResults(
        resultChannel: Channel<ChunkResult>,
        chunkCount: Int,
        inFlight: Semaphore,
    ): SnsPublishBatchResult {
        val pending = sortedMapOf<Int, ChunkResultData>()
        val successful = mutableListOf<SnsPublishBatchSuccess>()
        val failed = mutableListOf<SnsPublishBatchFailure>()
        var nextSequence = 0

        repeat(chunkCount) {
            val chunk = resultChannel.receive()
            pending[chunk.sequence] = chunk.result
            while (pending[nextSequence] != null) {
                val result = pending.remove(nextSequence) ?: break
                successful += result.successful
                failed += result.failed
                nextSequence++
                inFlight.release()
            }
        }
        return SnsPublishBatchResult(successful, failed)
    }

    private fun mapResponse(
        entries: List<SnsPublishBatchEntry>,
        response: PublishBatchResponse,
    ): ChunkResultData {
        val successful = response.successful().orEmpty()
        val failed = response.failed().orEmpty()
        val submittedIds = entries.map { it.id }
        val responseIds = successful.map { it.id() } + failed.map { it.id() }
        val protocol = SnsBatchProtocolException.from(submittedIds, responseIds)
        if (protocol.unknownEntryCount > 0 ||
            protocol.duplicateEntryCount > 0 ||
            protocol.missingEntryCount > 0
        ) {
            throw protocol
        }

        val successfulById = successful.associateBy { it.id() }
        val failedById = failed.associateBy { it.id() }
        val orderedSuccessful = submittedIds.mapNotNull { id ->
            successfulById[id]?.let { entry ->
                SnsPublishBatchSuccess(
                    entryId = id,
                    messageId = entry.messageId(),
                    sequenceNumber = entry.sequenceNumber(),
                )
            }
        }
        val orderedFailed = submittedIds.mapNotNull { id ->
            failedById[id]?.let { entry ->
                SnsPublishBatchFailure(
                    entryId = id,
                    code = entry.code(),
                    message = entry.message(),
                    senderFault = entry.senderFault(),
                )
            }
        }
        return ChunkResultData(orderedSuccessful, orderedFailed)
    }

    private data class Chunk(
        val sequence: Int,
        val entries: List<SnsPublishBatchEntry>,
    )

    private data class ChunkResult(
        val sequence: Int,
        val result: ChunkResultData,
    )

    private data class ChunkResultData(
        val successful: List<SnsPublishBatchSuccess>,
        val failed: List<SnsPublishBatchFailure>,
    )
}
