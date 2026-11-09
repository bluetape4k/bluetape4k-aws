package io.bluetape4k.aws.kinesis.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kinesis.model.PutRecordRequest

/**
 * DSL 블록으로 [PutRecordRequest]를 빌드합니다.
 *
 * ```kotlin
 * val req = putRecordRequest {
 *     streamName("my-stream")
 *     partitionKey("partition-1")
 *     data(SdkBytes.fromUtf8String("hello"))
 * }
 * ```
 */
inline fun putRecordRequest(
    builder: PutRecordRequest.Builder.() -> Unit,
): PutRecordRequest =
    PutRecordRequest.builder().apply(builder).build()

/**
 * 스트림 이름, 파티션 키, 데이터로 [PutRecordRequest]를 생성합니다.
 *
 * ## 동작/계약
 * - [streamName]이 blank이면 `IllegalArgumentException`을 던진다.
 * - [partitionKey]가 blank이면 `IllegalArgumentException`을 던진다.
 * - [explicitHashKey]가 지정되고 blank이면 `IllegalArgumentException`을 던진다.
 * - [data]는 필수 입력이다.
 *
 * ```kotlin
 * val req = putRecordRequestOf(
 *     streamName = "my-stream",
 *     partitionKey = "pk",
 *     explicitHashKey = "123",
 *     data = SdkBytes.fromUtf8String("hello world")
 * )
 * ```
 */
inline fun putRecordRequestOf(
    streamName: String,
    partitionKey: String,
    explicitHashKey: String? = null,
    data: SdkBytes,
    builder: PutRecordRequest.Builder.() -> Unit = {},
): PutRecordRequest {
    streamName.requireNotBlank("streamName")
    partitionKey.requireNotBlank("partitionKey")
    explicitHashKey?.requireNotBlank("explicitHashKey")

    return putRecordRequest {
        streamName(streamName)
        partitionKey(partitionKey)
        explicitHashKey?.let { this.explicitHashKey(it) }
        data(data)

        builder()
    }
}

/**
 * 기존 호출자와 JVM ABI를 유지하면서 데이터만 지정하는 오버로드입니다.
 */
inline fun putRecordRequestOf(
    streamName: String,
    partitionKey: String,
    data: SdkBytes,
    builder: PutRecordRequest.Builder.() -> Unit = {},
): PutRecordRequest = putRecordRequestOf(
    streamName = streamName,
    partitionKey = partitionKey,
    explicitHashKey = null,
    data = data,
    builder = builder,
)
