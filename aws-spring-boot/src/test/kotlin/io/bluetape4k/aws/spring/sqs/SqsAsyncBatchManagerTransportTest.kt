package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.codec.Base58
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.batchmanager.SqsAsyncBatchManager
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchResponse
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchResultEntry
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResultEntry
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageResponse
import software.amazon.awssdk.services.sqs.model.SqsException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class SqsAsyncBatchManagerTransportTest {

    @Test
    fun `manager adapter preserves request fields and source future cancellation`() {
        val manager = mockk<SqsAsyncBatchManager>()
        val sendRequest = slot<SendMessageRequest>()
        val deleteRequest = slot<DeleteMessageRequest>()
        val sendSource = CompletableFuture<SendMessageResponse>()
        val deleteSource = CompletableFuture<DeleteMessageResponse>()
        every { manager.sendMessage(capture(sendRequest)) } returns sendSource
        every { manager.deleteMessage(capture(deleteRequest)) } returns deleteSource
        val sendEntry = sendEntry("captured")
        val deleteEntry = deleteEntry("captured")
        val transport = SqsAsyncBatchManagerTransport(manager)

        val sendOutcome = transport.send(sendEntry)
        val deleteOutcome = transport.delete(deleteEntry)

        sendRequest.captured.queueUrl() shouldBeEqualTo sendEntry.request.queueUrl
        sendRequest.captured.messageBody() shouldBeEqualTo sendEntry.request.body
        sendRequest.captured.delaySeconds() shouldBeEqualTo sendEntry.request.delaySeconds
        sendRequest.captured.messageGroupId() shouldBeEqualTo sendEntry.request.messageGroupId
        sendRequest.captured.messageDeduplicationId() shouldBeEqualTo sendEntry.request.messageDeduplicationId
        sendRequest.captured.messageAttributes() shouldBeEqualTo sendEntry.request.messageAttributes
        deleteRequest.captured.queueUrl() shouldBeEqualTo deleteEntry.queueUrl
        deleteRequest.captured.receiptHandle() shouldBeEqualTo deleteEntry.receiptHandle

        sendOutcome.cancel(true).shouldBeTrue()
        deleteOutcome.cancel(true).shouldBeTrue()
        sendSource.isCancelled.shouldBeTrue()
        deleteSource.isCancelled.shouldBeTrue()
        verify(exactly = 0) { manager.close() }
    }

    @Test
    fun `manager adapter preserves send success fields and normalizes delete outcomes`() {
        val manager = mockk<SqsAsyncBatchManager>()
        val messageId = "message-${Base58.randomString(16)}"
        val sequenceNumber = "sequence-${Base58.randomString(16)}"
        val serviceCode = "ThrottlingException"
        val sendSource = CompletableFuture.completedFuture(
            SendMessageResponse.builder()
                .messageId(messageId)
                .sequenceNumber(sequenceNumber)
                .build(),
        )
        val deleteSuccessSource = CompletableFuture.completedFuture(DeleteMessageResponse.builder().build())
        val deleteServiceSource = CompletableFuture.failedFuture<DeleteMessageResponse>(
            SqsException.builder()
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(serviceCode).build())
                .build(),
        )
        val deleteTransportSource = CompletableFuture.failedFuture<DeleteMessageResponse>(
            SdkClientException.create("transport-${Base58.randomString(16)}"),
        )
        every { manager.sendMessage(any<SendMessageRequest>()) } returns sendSource
        every { manager.deleteMessage(any<DeleteMessageRequest>()) } returnsMany
            listOf(deleteSuccessSource, deleteServiceSource, deleteTransportSource)
        val transport = SqsAsyncBatchManagerTransport(manager)
        val sendEntry = sendEntry("success")
        val deleteSuccessEntry = deleteEntry("delete-success")
        val deleteServiceEntry = deleteEntry("delete-service")
        val deleteTransportEntry = deleteEntry("delete-transport")

        transport.send(sendEntry).join() shouldBeEqualTo SqsBatchOutcome.SendSuccess(
            entryId = sendEntry.entryId,
            messageId = messageId,
            sequenceNumber = sequenceNumber,
        )
        transport.delete(deleteSuccessEntry).join() shouldBeEqualTo
            SqsBatchOutcome.DeleteSuccess(deleteSuccessEntry.entryId)
        transport.delete(deleteServiceEntry).join() shouldBeEqualTo SqsBatchOutcome.Failure(
            SqsBatchEntryFailure(deleteServiceEntry.entryId, SqsBatchFailureKind.SERVICE, serviceCode),
        )
        transport.delete(deleteTransportEntry).join() shouldBeEqualTo SqsBatchOutcome.Failure(
            SqsBatchEntryFailure(deleteTransportEntry.entryId, SqsBatchFailureKind.TRANSPORT, null),
        )
    }

    @Test
    fun `real manager flushes on size and controlled interval without wall clock sleep`() {
        val client = mockk<SqsAsyncClient>()
        val captured = CopyOnWriteArrayList<SendMessageBatchRequest>()
        every { client.sendMessageBatch(any<SendMessageBatchRequest>()) } answers {
            firstArg<SendMessageBatchRequest>().let { request ->
                captured += request
                CompletableFuture.completedFuture(successfulSendResponse(request))
            }
        }
        val scheduler = ManualScheduledExecutor()
        val manager = manager(client, scheduler, maxBatchSize = 2)
        val transport = SqsAsyncBatchManagerTransport(manager)

        val firstEntry = sendEntry("size-1", queue = "queue-size")
        val secondEntry = sendEntry("size-2", queue = "queue-size")
        val first = transport.send(firstEntry)
        verify(exactly = 0) { client.sendMessageBatch(any<SendMessageBatchRequest>()) }
        val second = transport.send(secondEntry)

        verify(exactly = 1) { client.sendMessageBatch(any<SendMessageBatchRequest>()) }
        first.join().entryId shouldBeEqualTo firstEntry.entryId
        second.join().entryId shouldBeEqualTo secondEntry.entryId
        captured.single().entries() shouldHaveSize 2

        val intervalEntry = sendEntry("interval", queue = "queue-interval")
        val interval = transport.send(intervalEntry)
        verify(exactly = 1) { client.sendMessageBatch(any<SendMessageBatchRequest>()) }
        scheduler.runScheduledTasks()

        verify(exactly = 2) { client.sendMessageBatch(any<SendMessageBatchRequest>()) }
        interval.join().entryId shouldBeEqualTo intervalEntry.entryId
        manager.close()
        scheduler.shutdownNow()
        verify(exactly = 0) { client.close() }
    }

    @Test
    fun `real manager merges same queue across coroutines and separates queue and operation buffers`() = runTest {
        val client = mockk<SqsAsyncClient>()
        val sendBatches = CopyOnWriteArrayList<SendMessageBatchRequest>()
        val deleteBatches = CopyOnWriteArrayList<DeleteMessageBatchRequest>()
        every { client.sendMessageBatch(any<SendMessageBatchRequest>()) } answers {
            firstArg<SendMessageBatchRequest>().let { request ->
                sendBatches += request
                CompletableFuture.completedFuture(successfulSendResponse(request))
            }
        }
        every { client.deleteMessageBatch(any<DeleteMessageBatchRequest>()) } answers {
            firstArg<DeleteMessageBatchRequest>().let { request ->
                deleteBatches += request
                CompletableFuture.completedFuture(successfulDeleteResponse(request))
            }
        }
        val scheduler = ManualScheduledExecutor()
        val manager = manager(client, scheduler, maxBatchSize = 2)
        val transport = SqsAsyncBatchManagerTransport(manager)

        val sameQueue = listOf("same-1", "same-2").map { prefix ->
            async(Dispatchers.Default) { transport.send(sendEntry(prefix, queue = "queue-same")) }
        }.awaitAll()
        sameQueue.forEach { it.join() }

        sendBatches shouldHaveSize 1
        sendBatches.single().entries() shouldHaveSize 2

        val queueA = transport.send(sendEntry("queue-a", queue = "queue-a"))
        val queueB = transport.send(sendEntry("queue-b", queue = "queue-b"))
        val mixedSend = transport.send(sendEntry("mixed-send", queue = "queue-mixed"))
        val mixedDelete = transport.delete(deleteEntry("mixed-delete", queue = "queue-mixed"))
        scheduler.runScheduledTasks()

        listOf(queueA, queueB, mixedSend, mixedDelete).forEach { it.join() }
        sendBatches.map { it.queueUrl() }.toSet() shouldBeEqualTo
            setOf("queue-same", "queue-a", "queue-b", "queue-mixed")
        deleteBatches.map { it.queueUrl() } shouldBeEqualTo listOf("queue-mixed")
        manager.close()
        scheduler.shutdownNow()
    }

    @Test
    fun `real manager normalizes partial service and shared transport failures`() {
        val client = mockk<SqsAsyncClient>()
        every { client.sendMessageBatch(any<SendMessageBatchRequest>()) } answers {
            val request = firstArg<SendMessageBatchRequest>()
            CompletableFuture.completedFuture(
                SendMessageBatchResponse.builder()
                    .successful(
                        SendMessageBatchResultEntry.builder()
                            .id(request.entries().first().id())
                            .messageId("message-${Base58.randomString(16)}")
                            .build(),
                    )
                    .failed(
                        BatchResultErrorEntry.builder()
                            .id(request.entries().last().id())
                            .code("ThrottlingException")
                            .senderFault(false)
                            .message("service-${Base58.randomString(16)}")
                            .build(),
                    )
                    .build(),
            )
        } andThenAnswer {
            CompletableFuture.failedFuture(SdkClientException.create("transport-${Base58.randomString(16)}"))
        }
        val scheduler = ManualScheduledExecutor()
        val manager = manager(client, scheduler, maxBatchSize = 2)
        val transport = SqsAsyncBatchManagerTransport(manager)
        val successfulEntry = sendEntry("partial-success", queue = "queue-partial")
        val failedEntry = sendEntry("partial-failure", queue = "queue-partial")

        val successful = transport.send(successfulEntry)
        val failed = transport.send(failedEntry)
        val transportFirst = transport.send(sendEntry("transport-1", queue = "queue-transport"))
        val transportSecond = transport.send(sendEntry("transport-2", queue = "queue-transport"))

        successful.join().entryId shouldBeEqualTo successfulEntry.entryId
        failed.join() shouldBeEqualTo SqsBatchOutcome.Failure(
            SqsBatchEntryFailure(failedEntry.entryId, SqsBatchFailureKind.SERVICE, "ThrottlingException"),
        )
        listOf(transportFirst, transportSecond).forEach { future ->
            val outcome = future.join()
            outcome shouldBeEqualTo SqsBatchOutcome.Failure(
                SqsBatchEntryFailure(outcome.entryId, SqsBatchFailureKind.TRANSPORT, null),
            )
        }
        manager.close()
        scheduler.shutdownNow()
    }

    @Test
    fun `many queue URLs retain at most one active scheduled flush per queue`() {
        val client = mockk<SqsAsyncClient>(relaxed = true)
        val scheduler = ManualScheduledExecutor()
        val manager = manager(client, scheduler, maxBatchSize = 10)
        val transport = SqsAsyncBatchManagerTransport(manager)
        val queueCount = 32

        repeat(queueCount) { index ->
            transport.send(sendEntry("queue-$index", queue = "queue-$index"))
        }

        scheduler.activeTaskCount shouldBeLessOrEqualTo queueCount
        verify(exactly = 0) { client.sendMessageBatch(any<SendMessageBatchRequest>()) }
        manager.close()
        scheduler.activeTaskCount shouldBeEqualTo 0
        scheduler.shutdownNow()
    }

    private fun manager(
        client: SqsAsyncClient,
        scheduler: ManualScheduledExecutor,
        maxBatchSize: Int,
    ): SqsAsyncBatchManager = newSqsAsyncBatchManager(
        properties = SqsBatchProperties(
            enabled = true,
            maxBatchSize = maxBatchSize,
            flushInterval = Duration.ofSeconds(30),
            maxInFlightEntries = maxBatchSize * 2,
            schedulerThreads = 1,
            shutdownTimeout = Duration.ofSeconds(30),
        ),
        client = client,
        executor = scheduler,
    )

    private fun successfulSendResponse(request: SendMessageBatchRequest): SendMessageBatchResponse =
        SendMessageBatchResponse.builder()
            .successful(
                request.entries().map { entry ->
                    SendMessageBatchResultEntry.builder()
                        .id(entry.id())
                        .messageId("message-${Base58.randomString(16)}")
                        .build()
                },
            )
            .build()

    private fun successfulDeleteResponse(request: DeleteMessageBatchRequest): DeleteMessageBatchResponse =
        DeleteMessageBatchResponse.builder()
            .successful(request.entries().map { DeleteMessageBatchResultEntry.builder().id(it.id()).build() })
            .build()

    private fun sendEntry(prefix: String, queue: String = "queue-$prefix"): SqsBatchSendEntry =
        SqsBatchSendEntry(
            entryId = firstEntryId(prefix),
            request = SqsSendRequest(
                queueUrl = queue,
                body = Base58.randomString(16),
                delaySeconds = 1,
                messageGroupId = Base58.randomString(16),
                messageDeduplicationId = Base58.randomString(16),
            ),
        )

    private fun deleteEntry(prefix: String, queue: String = "queue-$prefix"): SqsBatchDeleteEntry =
        SqsBatchDeleteEntry(firstEntryId(prefix), queue, Base58.randomString(16))

    private fun firstEntryId(prefix: String): String = "$prefix-${Base58.randomString(16)}"
}

private class ManualScheduledExecutor : ScheduledThreadPoolExecutor(1) {
    private val scheduledTasks = CopyOnWriteArrayList<ManualScheduledFuture>()

    val activeTaskCount: Int get() = scheduledTasks.count { !it.isCancelled }

    override fun scheduleAtFixedRate(
        command: Runnable,
        initialDelay: Long,
        period: Long,
        unit: TimeUnit,
    ): ScheduledFuture<*> = ManualScheduledFuture(command).also(scheduledTasks::add)

    fun runScheduledTasks() {
        scheduledTasks.filter { !it.isCancelled }.forEach { it.command.run() }
    }
}

private class ManualScheduledFuture(
    val command: Runnable,
) : ScheduledFuture<Unit> {
    @Volatile
    private var cancelled = false

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        if (cancelled) return false
        cancelled = true
        return true
    }

    override fun isCancelled(): Boolean = cancelled

    override fun isDone(): Boolean = cancelled

    override fun get(): Unit = Unit

    override fun get(timeout: Long, unit: TimeUnit): Unit = Unit

    override fun getDelay(unit: TimeUnit): Long = 0L

    override fun compareTo(other: Delayed): Int = 0
}
