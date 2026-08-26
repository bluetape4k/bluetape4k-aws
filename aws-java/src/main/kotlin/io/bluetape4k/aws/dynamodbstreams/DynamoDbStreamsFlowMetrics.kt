package io.bluetape4k.aws.dynamodbstreams

/** DynamoDB Streams Flow의 선택적 관측 callback입니다. record payload는 전달하지 않습니다. */
interface DynamoDbStreamsFlowMetrics {
    fun onShardStarted(shardId: String) = Unit

    fun onBatch(shardId: String, recordCount: Int) = Unit

    fun onCheckpointSaved(shardId: String, sequenceNumber: String) = Unit

    fun onRetry(shardId: String, attempt: Int, cause: Throwable) = Unit

    fun onShardCompleted(shardId: String) = Unit
}

/** 관측이 필요하지 않을 때 사용하는 no-op metrics 구현입니다. */
object NoopDynamoDbStreamsFlowMetrics : DynamoDbStreamsFlowMetrics

/** 테스트나 애플리케이션 callback을 조합할 때 사용할 간단한 metrics adapter입니다. */
class LambdaDynamoDbStreamsFlowMetrics(
    private val onStarted: (String) -> Unit = {},
    private val onBatchRead: (String, Int) -> Unit = { _, _ -> },
    private val onCheckpoint: (String, String) -> Unit = { _, _ -> },
    private val onRetrying: (String, Int, Throwable) -> Unit = { _, _, _ -> },
    private val onCompleted: (String) -> Unit = {},
) : DynamoDbStreamsFlowMetrics {
    override fun onShardStarted(shardId: String) = onStarted(shardId)

    override fun onBatch(shardId: String, recordCount: Int) = onBatchRead(shardId, recordCount)

    override fun onCheckpointSaved(shardId: String, sequenceNumber: String) = onCheckpoint(shardId, sequenceNumber)

    override fun onRetry(shardId: String, attempt: Int, cause: Throwable) = onRetrying(shardId, attempt, cause)

    override fun onShardCompleted(shardId: String) = onCompleted(shardId)
}
