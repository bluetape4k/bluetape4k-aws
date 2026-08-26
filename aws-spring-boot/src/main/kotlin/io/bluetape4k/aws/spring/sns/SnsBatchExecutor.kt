package io.bluetape4k.aws.spring.sns

import software.amazon.awssdk.services.sns.model.PublishBatchResponse

/** AWS SDK v2 PublishBatch 응답을 공통 bounded coordinator에 연결하는 내부 adapter입니다. */
private object SnsBatchExecutorConstructorMarker

internal class SnsBatchExecutor private constructor(
    publishChunk: suspend (
        topicArn: String,
        entries: List<SnsPublishBatchEntry>,
    ) -> PublishBatchResponse,
    onCompletedEntryIds: (List<String>) -> Unit,
    @Suppress("UNUSED_PARAMETER") marker: SnsBatchExecutorConstructorMarker,
) {

    constructor(
        publishChunk: suspend (
            topicArn: String,
            entries: List<SnsPublishBatchEntry>,
        ) -> PublishBatchResponse,
    ) : this(publishChunk, {}, SnsBatchExecutorConstructorMarker)

    constructor(
        publishChunk: suspend (
            topicArn: String,
            entries: List<SnsPublishBatchEntry>,
        ) -> PublishBatchResponse,
        onCompletedEntryIds: (List<String>) -> Unit,
    ) : this(publishChunk, onCompletedEntryIds, SnsBatchExecutorConstructorMarker)

    private val coordinator = SnsBatchExecutionCoordinator(
        publishChunk = publishChunk,
        mapChunk = SnsBatchResponseMapper::map,
        onCompletedEntryIds = onCompletedEntryIds,
    )

    suspend fun execute(
        request: SnsPublishBatchRequest,
        options: SnsBatchExecutionOptions = SnsBatchExecutionOptions(),
    ): SnsPublishBatchResult = coordinator.execute(request, options)
}
