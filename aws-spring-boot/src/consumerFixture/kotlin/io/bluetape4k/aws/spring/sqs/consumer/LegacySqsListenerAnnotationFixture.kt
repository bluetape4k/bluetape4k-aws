package io.bluetape4k.aws.spring.sqs.consumer

import io.bluetape4k.aws.spring.sqs.SqsListener
import io.bluetape4k.aws.spring.sqs.SqsReceivedMessage

/** 기존 단건 annotation consumer의 source 호환성 fixture입니다. */
class LegacySqsListenerAnnotationFixture {
    @SqsListener(queue = "orders")
    suspend fun handle(message: SqsReceivedMessage) {
        check(message.body.isNotBlank())
    }
}
