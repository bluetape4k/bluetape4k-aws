package io.bluetape4k.aws.spring.sns

import software.amazon.awssdk.services.sns.model.PublishBatchResponse

/** PublishBatch 응답과 typed strategy 결과를 입력 순서의 내부 chunk 결과로 정규화합니다. */
internal object SnsBatchResponseMapper {

    /** AWS SDK 응답의 ID protocol을 검증하고 typed 결과로 변환합니다. */
    fun map(
        entries: List<SnsPublishBatchEntry>,
        response: PublishBatchResponse,
    ): SnsBatchChunkResult {
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
        return SnsBatchChunkResult(
            successful = submittedIds.mapNotNull { id ->
                successfulById[id]?.let { entry ->
                    SnsPublishBatchSuccess(
                        entryId = id,
                        messageId = entry.messageId(),
                        sequenceNumber = entry.sequenceNumber(),
                    )
                }
            },
            failed = submittedIds.mapNotNull { id ->
                failedById[id]?.let { entry ->
                    SnsPublishBatchFailure(
                        entryId = id,
                        code = entry.code(),
                        message = entry.message(),
                        senderFault = entry.senderFault(),
                    )
                }
            },
        )
    }

    /** strategy port가 반환한 typed 결과의 ID protocol을 검증합니다. */
    fun map(
        entries: List<SnsPublishBatchEntry>,
        result: SnsPublishBatchResult,
    ): SnsBatchChunkResult {
        val submittedIds = entries.map { it.id }
        val resultIds = result.successful.map { it.entryId } + result.failed.map { it.entryId }
        val submittedSet = submittedIds.toSet()
        val resultCounts = resultIds.groupingBy { it }.eachCount()
        val hasUnknown = resultIds.any { it !in submittedSet }
        val hasDuplicate = resultCounts.values.any { it > 1 }
        val hasMissing = submittedSet.any { it !in resultIds.toSet() }
        if (hasUnknown || hasDuplicate || hasMissing) {
            throw SnsBatchExecutionContractException(SnsBatchExecutionContractError.INVALID_RESULT)
        }

        val successfulById = result.successful.associateBy { it.entryId }
        val failedById = result.failed.associateBy { it.entryId }
        return SnsBatchChunkResult(
            successful = submittedIds.mapNotNull(successfulById::get),
            failed = submittedIds.mapNotNull(failedById::get),
        )
    }
}
