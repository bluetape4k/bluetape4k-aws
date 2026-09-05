package io.bluetape4k.aws.kotlin.kinesis.model

import aws.sdk.kotlin.services.kinesis.model.GetShardIteratorRequest
import aws.sdk.kotlin.services.kinesis.model.ShardIteratorType
import io.bluetape4k.support.requireNotBlank

/**
 * 스트림 이름, 샤드 ID, 이터레이터 타입으로 [GetShardIteratorRequest]를 생성합니다.
 *
 * ## 동작/계약
 * - [streamName]이 blank이면 `IllegalArgumentException`을 던진다.
 * - [shardId]가 blank이면 `IllegalArgumentException`을 던진다.
 * - [dryRun]은 request의 `DryRun` 필드에 매핑된다. 마지막에 실행되는 [builder]가 `false`로
 *   덮어쓰면 DryRun을 해제하고, `null`로 덮어쓰면 필드 전송을 생략한다.
 * - 이 helper는 request만 만들며 서비스 호출이나 서비스 예외를 발생시키지 않는다.
 *
 * ```kotlin
 * val req = getShardIteratorRequestOf(
 *     streamName = "my-stream",
 *     shardId = "shardId-000000000000",
 *     type = ShardIteratorType.TrimHorizon
 * )
 * ```
 *
 * @param dryRun request의 `DryRun` 값. 기본값은 `false`입니다.
 * @param builder 마지막에 실행되는 [GetShardIteratorRequest.Builder] 설정입니다.
 */
inline fun getShardIteratorRequestOf(
    streamName: String,
    shardId: String,
    type: ShardIteratorType = ShardIteratorType.TrimHorizon,
    dryRun: Boolean = false,
    crossinline builder: GetShardIteratorRequest.Builder.() -> Unit = {},
): GetShardIteratorRequest {
    streamName.requireNotBlank("streamName")
    shardId.requireNotBlank("shardId")
    return GetShardIteratorRequest {
        this.streamName = streamName
        this.shardId = shardId
        this.shardIteratorType = type
        this.dryRun = dryRun
        builder()
    }
}

@Deprecated("Binary compatibility overload", level = DeprecationLevel.HIDDEN)
inline fun getShardIteratorRequestOf(
    streamName: String,
    shardId: String,
    type: ShardIteratorType = ShardIteratorType.TrimHorizon,
    crossinline builder: GetShardIteratorRequest.Builder.() -> Unit = {},
): GetShardIteratorRequest = getShardIteratorRequestOf(streamName, shardId, type, false, builder)
