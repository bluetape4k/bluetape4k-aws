package io.bluetape4k.aws.examples.spring.sqs

import io.bluetape4k.aws.spring.sqs.SqsListener
import org.springframework.stereotype.Component
import java.util.concurrent.CopyOnWriteArrayList

@Component
class ReceivedOrderStore {
    private val messages = CopyOnWriteArrayList<String>()

    fun record(message: String) {
        messages += message
    }

    fun recent(): List<String> =
        messages.toList()

    fun clear() {
        messages.clear()
    }
}

@Component
class OrderMessageListener(
    private val store: ReceivedOrderStore,
) {

    @SqsListener(queue = "\${example.aws.sqs.listener-queue:orders}", maxMessages = 1, waitTimeSeconds = 1)
    fun handle(message: String) {
        store.record(message)
    }
}
