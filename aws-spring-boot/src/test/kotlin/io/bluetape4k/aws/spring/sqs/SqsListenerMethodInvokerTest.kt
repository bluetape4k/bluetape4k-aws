package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.mockk
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class SqsListenerMethodInvokerTest {

    @Test
    fun `invoker executes suspend listener on injected dispatcher`() = runSuspendIO {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "sqs-invoker-test")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val listener = DispatcherProbe()
            val method = DispatcherProbe::class.java.declaredMethods.single { it.name == "handle" }
            val invoker = SqsListenerMethodInvoker(
                bean = listener,
                method = method,
                messageConverter = NoopSqsMessageConverter,
                dispatcher = dispatcher,
            )

            invoker.invoke(message(), mockk())

            listener.threadName.get() shouldContain "sqs-invoker-test"
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `batch invoker maps received messages to a single list argument`() = runSuspendIO {
        val listener = BatchProbe()
        val method = BatchProbe::class.java.declaredMethods.single { it.name == "handle" }
        val invoker = SqsListenerMethodInvoker(listener, method, NoopSqsMessageConverter)
        invoker.validateBatchSignature()

        invoker.invokeBatch(listOf(message(), message("message-2")), mockk())

        listener.messageIds shouldContain "message-1"
        listener.messageIds shouldContain "message-2"
    }

    @Test
    fun `batch invoker rejects unsupported list element shapes`() {
        val method = InvalidBatchProbe::class.java.declaredMethods.single { it.name == "nullable" }

        val failure = runCatching {
            SqsListenerMethodInvoker(InvalidBatchProbe(), method, NoopSqsMessageConverter).validateBatchSignature()
        }.exceptionOrNull()

        failure?.message shouldContain "unsupported batch element type"
    }

    class DispatcherProbe {
        val threadName = AtomicReference("")

        @Suppress("UNUSED_PARAMETER")
        suspend fun handle(payload: String) {
            threadName.set(Thread.currentThread().name)
        }
    }

    class BatchProbe {
        val messageIds = mutableListOf<String>()

        @Suppress("UNUSED_PARAMETER")
        suspend fun handle(messages: List<SqsReceivedMessage>, acknowledgement: SqsBatchAcknowledgement) {
            messageIds += messages.map { it.messageId }
        }
    }

    class InvalidBatchProbe {
        fun nullable(messages: List<String?>) = messages.size
    }

    private fun message(messageId: String = "message-1"): SqsReceivedMessage =
        SqsReceivedMessage(
            queueUrl = "https://sqs.local/orders",
            message = software.amazon.awssdk.services.sqs.model.Message.builder()
                .messageId(messageId)
                .receiptHandle("receipt-1")
                .body("payload")
                .build(),
        )
}
