package io.bluetape4k.aws.spring.sns

import software.amazon.awssdk.services.sns.model.PublishBatchResponse

/** AWS SDK v2 PublishBatch 응답을 공통 bounded coordinator에 연결하는 내부 adapter입니다. */
internal class SnsBatchExecutor(
    publishChunk: suspend (
        topicArn: String,
        entries: List<SnsPublishBatchEntry>,
    ) -> PublishBatchResponse,
    onCompletedEntryIds: (List<String>) -> Unit = {},
) {

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
