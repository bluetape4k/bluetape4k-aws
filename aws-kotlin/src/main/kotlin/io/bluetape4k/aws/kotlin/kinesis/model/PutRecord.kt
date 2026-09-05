package io.bluetape4k.aws.kotlin.kinesis.model

import aws.sdk.kotlin.services.kinesis.model.PutRecordRequest
import io.bluetape4k.support.requireNotBlank

/**
 * 스트림 이름, 파티션 키, 데이터로 [PutRecordRequest]를 생성합니다.
 *
 * ## 동작/계약
 * - [streamName]이 blank이면 `IllegalArgumentException`을 던진다.
 * - [partitionKey]가 blank이면 `IllegalArgumentException`을 던진다.
 * - [dryRun]은 request의 `DryRun` 필드에 매핑된다. 마지막에 실행되는 [builder]가 `false`로
 *   덮어쓰면 DryRun을 해제하고, `null`로 덮어쓰면 필드 전송을 생략한다.
 * - 이 helper는 request만 만들며 서비스 호출이나 서비스 예외를 발생시키지 않는다.
 *
 * ```kotlin
 * val req = putRecordRequestOf(
 *     streamName = "my-stream",
 *     partitionKey = "pk",
 *     data = "hello".toByteArray()
 * )
 * ```
 *
 * @param dryRun request의 `DryRun` 값. 기본값은 `false`입니다.
 * @param builder 마지막에 실행되는 [PutRecordRequest.Builder] 설정입니다.
 */
inline fun putRecordRequestOf(
    streamName: String,
    partitionKey: String,
    data: ByteArray,
    dryRun: Boolean = false,
    crossinline builder: PutRecordRequest.Builder.() -> Unit = {},
): PutRecordRequest {
    streamName.requireNotBlank("streamName")
    partitionKey.requireNotBlank("partitionKey")
    return PutRecordRequest {
        this.streamName = streamName
        this.partitionKey = partitionKey
        this.data = data
        this.dryRun = dryRun
        builder()
    }
}

@Deprecated("Binary compatibility overload", level = DeprecationLevel.HIDDEN)
inline fun putRecordRequestOf(
    streamName: String,
    partitionKey: String,
    data: ByteArray,
    crossinline builder: PutRecordRequest.Builder.() -> Unit = {},
): PutRecordRequest = putRecordRequestOf(streamName, partitionKey, data, false, builder)
