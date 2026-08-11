package io.bluetape4k.aws.spring.sqs

/**
 * 하나의 batch 관측을 상관시키는 불투명한 런타임 식별자입니다.
 *
 * 값에는 queue URL, receipt handle, 메시지 본문 또는 message ID를 넣지 않습니다.
 */
data class SqsListenerBatchCorrelation(
    val generation: Long,
    val pollerId: Int,
    val batchSequence: Long,
)
