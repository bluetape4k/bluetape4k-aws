package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.mockk
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertSame
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

    @Test
    fun `phase callback preserves conversion and handler failure identity`() = runSuspendIO {
        val conversionFailure = IllegalArgumentException("conversion")
        val converter = object : SqsMessageConverter {
            override fun convert(message: SqsReceivedMessage, targetType: Class<*>): Any = throw conversionFailure
        }
        val conversionInvoker = SqsListenerMethodInvoker(
            ConvertedProbe(),
            ConvertedProbe::class.java.declaredMethods.single { it.name == "handle" },
            converter,
        )
        var conversionEnteredHandler = false

        val actualConversionFailure = runCatching {
            conversionInvoker.invoke(message(), mockk()) { conversionEnteredHandler = true }
        }.exceptionOrNull()

        assertSame(conversionFailure, actualConversionFailure)
        conversionEnteredHandler shouldBeEqualTo false

        val handlerFailure = IllegalStateException("handler")
        val handlerInvoker = SqsListenerMethodInvoker(
            ThrowingProbe(handlerFailure),
            ThrowingProbe::class.java.declaredMethods.single { it.name == "handle" },
            NoopSqsMessageConverter,
        )
        var handlerEntered = false

        val actualHandlerFailure = runCatching {
            handlerInvoker.invoke(message(), mockk()) { handlerEntered = true }
        }.exceptionOrNull()

        assertSame(handlerFailure, actualHandlerFailure)
        handlerEntered shouldBeEqualTo true
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

    class ConvertedProbe {
        @Suppress("UNUSED_PARAMETER")
        fun handle(payload: ConvertedPayload) = Unit
    }

    class ConvertedPayload

    class ThrowingProbe(
        private val failure: RuntimeException,
    ) {
        @Suppress("UNUSED_PARAMETER")
        fun handle(payload: String): Nothing = throw failure
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
