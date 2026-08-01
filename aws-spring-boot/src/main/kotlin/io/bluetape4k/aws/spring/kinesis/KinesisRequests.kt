package io.bluetape4k.aws.spring.kinesis

import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType
import java.io.Serializable

/**
 * Kinesis 스트림에 레코드 하나를 게시하는 요청입니다.
 *
 * ## 계약
 *
 * 호출자가 타입이 같은 위치 인수의 순서를 실수로 바꾸지 않도록 스트림과 파티션 키 값을
 * 명명된 속성으로 감쌉니다.
 */
data class KinesisPutRecordRequest(
    val streamName: String,
    val partitionKey: String,
    val data: SdkBytes,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 샤드 반복자를 가져오는 요청입니다.
 *
 * ## 계약
 *
 * AWS SDK 샤드 반복자 요청을 반영하면서 Spring 애플리케이션 코드에서 스트림과 샤드 식별자를
 * 명시적으로 유지합니다.
 */
data class KinesisShardIteratorRequest(
    val streamName: String,
    val shardId: String,
    val type: ShardIteratorType = ShardIteratorType.TRIM_HORIZON,
    val startingSequenceNumber: String? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Kinesis 샤드 하나에서 레코드를 cold Flow로 수집하는 요청입니다.
 *
 * ## 계약
 *
 * Flow는 호출자가 수집하는 단일 샤드 흐름입니다. 이 요청은 리스너 컨테이너, 임대 조정,
 * 체크포인트 영속화를 제공하지 않습니다.
 */
data class KinesisRecordFlowRequest(
    val streamName: String,
    val shardId: String,
    val position: KinesisStartingPosition = KinesisStartingPosition.TrimHorizon,
    val options: KinesisRecordFlowOptions? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
