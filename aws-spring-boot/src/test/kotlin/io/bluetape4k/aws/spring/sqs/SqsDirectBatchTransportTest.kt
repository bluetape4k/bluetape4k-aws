package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.codec.Base58
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageResponse
import software.amazon.awssdk.services.sqs.model.SqsException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

class SqsDirectBatchTransportTest {

    @Test
    fun `direct send maps every request field and submits once without closing client`() {
        val client = mockk<SqsAsyncClient>()
        val source = CompletableFuture.completedFuture(
            SendMessageResponse.builder()
                .messageId("message-${Base58.randomString(16)}")
                .sequenceNumber("sequence-${Base58.randomString(16)}")
                .build(),
        )
        val captured = mutableListOf<SendMessageRequest>()
        every { client.sendMessage(any<Consumer<SendMessageRequest.Builder>>()) } answers {
            val builder = SendMessageRequest.builder()
            firstArg<Consumer<SendMessageRequest.Builder>>().accept(builder)
            captured += builder.build()
            source
        }
        val attribute = MessageAttributeValue.builder()
            .dataType("String")
            .stringValue(Base58.randomString(16))
            .build()
        val entry = SqsBatchSendEntry(
            entryId = entryId("send"),
            request = SqsSendRequest(
                queueUrl = "https://sqs.local/${Base58.randomString(16)}.fifo",
                body = Base58.randomString(16),
                delaySeconds = 3,
                messageGroupId = Base58.randomString(16),
                messageDeduplicationId = Base58.randomString(16),
                messageAttributes = mapOf("trace" to attribute),
            ),
        )
        val transport = DirectSqsBatchTransport(client)

        val outcome = transport.send(entry).join()

        captured shouldHaveSize 1
        with(captured.single()) {
            queueUrl() shouldBeEqualTo entry.request.queueUrl
            messageBody() shouldBeEqualTo entry.request.body
            delaySeconds() shouldBeEqualTo entry.request.delaySeconds
            messageGroupId() shouldBeEqualTo entry.request.messageGroupId
            messageDeduplicationId() shouldBeEqualTo entry.request.messageDeduplicationId
            messageAttributes() shouldBeEqualTo entry.request.messageAttributes
        }
        outcome.shouldBeInstanceOf<SqsBatchOutcome.SendSuccess>()
        outcome.entryId shouldBeEqualTo entry.entryId
        outcome.messageId shouldBeEqualTo source.join().messageId()
        outcome.sequenceNumber shouldBeEqualTo source.join().sequenceNumber()
        verify(exactly = 1) { client.sendMessage(any<Consumer<SendMessageRequest.Builder>>()) }
        verify(exactly = 0) { client.close() }
    }

    @Test
    fun `direct delete maps receipt handle and submits once without closing client`() {
        val client = mockk<SqsAsyncClient>()
        val source = CompletableFuture.completedFuture(DeleteMessageResponse.builder().build())
        val captured = mutableListOf<DeleteMessageRequest>()
        every { client.deleteMessage(any<Consumer<DeleteMessageRequest.Builder>>()) } answers {
            val builder = DeleteMessageRequest.builder()
            firstArg<Consumer<DeleteMessageRequest.Builder>>().accept(builder)
            captured += builder.build()
            source
        }
        val entry = SqsBatchDeleteEntry(
            entryId = entryId("delete"),
            queueUrl = "https://sqs.local/${Base58.randomString(16)}",
            receiptHandle = Base58.randomString(16),
        )
        val transport = DirectSqsBatchTransport(client)

        val outcome = transport.delete(entry).join()

        captured shouldHaveSize 1
        captured.single().queueUrl() shouldBeEqualTo entry.queueUrl
        captured.single().receiptHandle() shouldBeEqualTo entry.receiptHandle
        outcome shouldBeEqualTo SqsBatchOutcome.DeleteSuccess(entry.entryId)
        verify(exactly = 1) { client.deleteMessage(any<Consumer<DeleteMessageRequest.Builder>>()) }
        verify(exactly = 0) { client.close() }
    }

    @Test
    fun `direct transport normalizes service transport and SDK future cancellation outcomes`() {
        val serviceFuture = CompletableFuture<SendMessageResponse>()
        val transportFuture = CompletableFuture<SendMessageResponse>()
        val cancelledFuture = CompletableFuture<SendMessageResponse>()
        val client = mockk<SqsAsyncClient>()
        every { client.sendMessage(any<Consumer<SendMessageRequest.Builder>>()) } returnsMany
            listOf(serviceFuture, transportFuture, cancelledFuture)
        val transport = DirectSqsBatchTransport(client)
        val serviceEntry = sendEntry("service")
        val transportEntry = sendEntry("transport")
        val cancelledEntry = sendEntry("cancelled")

        val serviceOutcome = transport.send(serviceEntry)
        val transportOutcome = transport.send(transportEntry)
        val cancelledOutcome = transport.send(cancelledEntry)
        serviceFuture.completeExceptionally(
            SqsException.builder()
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("ThrottlingException").build())
                .build(),
        )
        transportFuture.completeExceptionally(SdkClientException.create("transport-${Base58.randomString(16)}"))
        cancelledFuture.cancel(false)

        serviceOutcome.join() shouldBeEqualTo SqsBatchOutcome.Failure(
            SqsBatchEntryFailure(serviceEntry.entryId, SqsBatchFailureKind.SERVICE, "ThrottlingException"),
        )
        transportOutcome.join() shouldBeEqualTo SqsBatchOutcome.Failure(
            SqsBatchEntryFailure(transportEntry.entryId, SqsBatchFailureKind.TRANSPORT, null),
        )
        cancelledOutcome.join() shouldBeEqualTo SqsBatchOutcome.Failure(
            SqsBatchEntryFailure(cancelledEntry.entryId, SqsBatchFailureKind.TRANSPORT, null),
        )
        cancelledOutcome.isCancelled.shouldBeFalse()
    }

    @Test
    fun `direct transport normalizes synchronous service and transport submission failures`() {
        val serviceEntry = sendEntry("sync-service")
        val serviceClient = mockk<SqsAsyncClient>()
        every { serviceClient.sendMessage(any<Consumer<SendMessageRequest.Builder>>()) } throws
            SqsException.builder()
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("ThrottlingException").build())
                .build()
        val transportEntry = SqsBatchDeleteEntry(
            entryId = entryId("sync-transport"),
            queueUrl = "https://sqs.local/${Base58.randomString(16)}",
            receiptHandle = Base58.randomString(16),
        )
        val transportClient = mockk<SqsAsyncClient>()
        every { transportClient.deleteMessage(any<Consumer<DeleteMessageRequest.Builder>>()) } throws
            SdkClientException.create("transport-${Base58.randomString(16)}")

        val serviceOutcome = DirectSqsBatchTransport(serviceClient).send(serviceEntry).join()
        val transportOutcome = DirectSqsBatchTransport(transportClient).delete(transportEntry).join()

        serviceOutcome shouldBeEqualTo SqsBatchOutcome.Failure(
            SqsBatchEntryFailure(serviceEntry.entryId, SqsBatchFailureKind.SERVICE, "ThrottlingException"),
        )
        transportOutcome shouldBeEqualTo SqsBatchOutcome.Failure(
            SqsBatchEntryFailure(transportEntry.entryId, SqsBatchFailureKind.TRANSPORT, null),
        )
    }

    @Test
    fun `caller cancellation cancels the source future without becoming an entry outcome`() {
        val source = CountingCompletableFuture<SendMessageResponse>()
        val client = mockk<SqsAsyncClient>()
        every { client.sendMessage(any<Consumer<SendMessageRequest.Builder>>()) } returns source
        val mapped = DirectSqsBatchTransport(client).send(sendEntry("caller"))

        mapped.cancel(true).shouldBeTrue()

        mapped.isCancelled.shouldBeTrue()
        source.isCancelled.shouldBeTrue()
        mapped.cancel(false)
        source.cancelCount.get() shouldBeEqualTo 1
        source.cancelArguments shouldBeEqualTo listOf(false)
        mapped.handle { value, failure -> value to failure }.join().let { (value, failure) ->
            value.shouldBeNull()
            failure.shouldBeInstanceOf<java.util.concurrent.CancellationException>()
        }
    }

    private fun sendEntry(prefix: String): SqsBatchSendEntry = SqsBatchSendEntry(
        entryId(prefix),
        SqsSendRequest("https://sqs.local/${Base58.randomString(16)}", Base58.randomString(16)),
    )

    private fun entryId(prefix: String): String = "$prefix-${Base58.randomString(16)}"

    private class CountingCompletableFuture<T> : CompletableFuture<T>() {
        val cancelCount = AtomicInteger()
        val cancelArguments = mutableListOf<Boolean>()

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            cancelCount.incrementAndGet()
            cancelArguments += mayInterruptIfRunning
            return super.cancel(mayInterruptIfRunning)
        }
    }
}
