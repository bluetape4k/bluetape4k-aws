package io.bluetape4k.aws.spring.sqs.consumer

import io.bluetape4k.aws.spring.sqs.SqsAcknowledgementAction
import io.bluetape4k.aws.spring.sqs.SqsListenerInterceptor
import io.bluetape4k.aws.spring.sqs.SqsListenerInvocationContext
import io.bluetape4k.aws.spring.sqs.SqsReceivedMessage

/** correlation-aware overload을 구현하지 않은 기존 interceptor source fixture입니다. */
class LegacySqsListenerInterceptorFixture: SqsListenerInterceptor {
    override suspend fun beforeReceive(listenerId: String, queueUrl: String) = Unit

    override suspend fun afterReceive(
        listenerId: String,
        queueUrl: String,
        messages: List<SqsReceivedMessage>,
        error: Throwable?,
    ) = Unit

    override suspend fun beforeHandle(context: SqsListenerInvocationContext) = Unit

    override suspend fun afterHandle(context: SqsListenerInvocationContext, error: Throwable?) = Unit

    override suspend fun beforeAcknowledgement(
        context: SqsListenerInvocationContext,
        action: SqsAcknowledgementAction,
    ) = Unit

    override suspend fun afterAcknowledgement(
        context: SqsListenerInvocationContext,
        action: SqsAcknowledgementAction,
        error: Throwable?,
    ) = Unit
}
