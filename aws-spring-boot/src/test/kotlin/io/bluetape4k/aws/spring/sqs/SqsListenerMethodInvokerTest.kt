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

    class DispatcherProbe {
        val threadName = AtomicReference("")

        @Suppress("UNUSED_PARAMETER")
        suspend fun handle(payload: String) {
            threadName.set(Thread.currentThread().name)
        }
    }

    private fun message(): SqsReceivedMessage =
        SqsReceivedMessage(
            queueUrl = "https://sqs.local/orders",
            message = software.amazon.awssdk.services.sqs.model.Message.builder()
                .messageId("message-1")
                .receiptHandle("receipt-1")
                .body("payload")
                .build(),
        )
}
