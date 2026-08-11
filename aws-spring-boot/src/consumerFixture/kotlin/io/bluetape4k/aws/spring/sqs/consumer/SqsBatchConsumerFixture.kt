package io.bluetape4k.aws.spring.sqs.consumer

import io.bluetape4k.aws.spring.sqs.SqsBatchAcknowledgement
import io.bluetape4k.aws.spring.sqs.SqsListener
import io.bluetape4k.aws.spring.sqs.SqsListenerBatchCorrelation
import io.bluetape4k.aws.spring.sqs.SqsListenerInterceptor
import io.bluetape4k.aws.spring.sqs.SqsListenerInvocationContext
import io.bluetape4k.aws.spring.sqs.SqsReceivedMessage

/** batch listener, manual partial acknowledgement, correlation consumer fixture입니다. */
class SqsBatchConsumerFixture {
    @SqsListener(queue = "orders", batch = true, maxMessages = 10)
    suspend fun handle(
        messages: List<SqsReceivedMessage>,
        acknowledgement: SqsBatchAcknowledgement,
    ) {
        acknowledgement.acknowledge(messages.take(1))
    }

    class CorrelationInterceptor: SqsListenerInterceptor {
        override suspend fun beforeReceive(
            listenerId: String,
            queueUrl: String,
            correlation: SqsListenerBatchCorrelation,
        ) = check(correlation.batchSequence >= 0)

        override suspend fun beforeBatchHandle(
            context: SqsListenerInvocationContext,
            correlation: SqsListenerBatchCorrelation,
            batchSize: Int,
        ) = check(batchSize >= 0)
    }
}
