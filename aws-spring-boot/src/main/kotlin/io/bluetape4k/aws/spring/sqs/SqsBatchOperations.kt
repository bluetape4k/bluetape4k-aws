package io.bluetape4k.aws.spring.sqs

/** SQS entry를 제한된 동시성으로 전송하거나 삭제하는 비동기 배치 연산입니다. */
interface SqsBatchOperations {

    suspend fun sendMany(
        entries: Collection<SqsBatchSendEntry>,
        failureStrategy: SendBatchFailureStrategy = SendBatchFailureStrategy.RETURN,
    ): SqsSendManyResult

    suspend fun deleteMany(entries: Collection<SqsBatchDeleteEntry>): SqsDeleteManyResult
}
