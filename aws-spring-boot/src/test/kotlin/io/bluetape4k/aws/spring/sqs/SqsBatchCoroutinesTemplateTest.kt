package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.codec.Base58
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledThreadPoolExecutor

class SqsBatchCoroutinesTemplateTest {

    @Test
    fun `direct and batch templates preserve requests and return the same outcomes`() = runTest {
        val groupId = "group-${Base58.randomString(16)}"
        val sends = listOf(
            sendEntry("parity-success", groupId = groupId),
            sendEntry("parity-failure", groupId = groupId),
        )
        val deletes = listOf(deleteEntry("parity-delete"))
        val sendOutcomes = listOf(
            sendSuccess(sends[0]),
            SqsBatchOutcome.Failure(
                SqsBatchEntryFailure(sends[1].entryId, SqsBatchFailureKind.SERVICE, "ThrottlingException"),
            ),
        )
        val deleteOutcome = SqsBatchOutcome.DeleteSuccess(deletes.single().entryId)

        val direct = templateWithOutcomes(sendOutcomes, deleteOutcome, batchEnabled = false)
        val batch = templateWithOutcomes(sendOutcomes, deleteOutcome, batchEnabled = true)

        val directSend = direct.template.sendMany(sends)
        val batchSend = batch.template.sendMany(sends)
        directSend shouldBeEqualTo batchSend
        directSend.successful.map { it.entryId } shouldBeEqualTo listOf(sends[0].entryId)
        directSend.failed.map { it.entryId } shouldBeEqualTo listOf(sends[1].entryId)
        direct.transport.sendEntries shouldBeEqualTo sends
        batch.transport.sendEntries shouldBeEqualTo sends

        val directDelete = direct.template.deleteMany(deletes)
        val batchDelete = batch.template.deleteMany(deletes)
        directDelete shouldBeEqualTo batchDelete
        direct.transport.deleteEntries shouldBeEqualTo deletes
        batch.transport.deleteEntries shouldBeEqualTo deletes

        direct.template.close()
        batch.template.close()
    }

    @Test
    fun `send throw and validation contracts are delegated before transport calls`() = runTest {
        val entry = sendEntry("throw")
        val transport = CoordinatorTestTransport().apply {
            enqueueSend(
                CompletableFuture.completedFuture(
                    SqsBatchOutcome.Failure(
                        SqsBatchEntryFailure(entry.entryId, SqsBatchFailureKind.TRANSPORT, null),
                    ),
                ),
            )
        }
        val template: SqsBatchOperations = template(transport)

        val failure = assertFailsWith<SqsSendBatchFailedException> {
            template.sendMany(listOf(entry), SendBatchFailureStrategy.THROW)
        }
        failure.result.failed shouldHaveSize 1

        val duplicateId = entryId("duplicate-template")
        assertFailsWith<IllegalArgumentException> {
            template.sendMany(
                listOf(sendEntry("first", duplicateId), sendEntry("second", duplicateId)),
            )
        }
        transport.sendEntries shouldHaveSize 1
    }

    private fun templateWithOutcomes(
        sends: List<SqsBatchOutcome>,
        delete: SqsBatchOutcome.DeleteSuccess,
        batchEnabled: Boolean,
    ): TemplateFixture {
        val transport = CoordinatorTestTransport().apply {
            sends.forEach { enqueueSend(CompletableFuture.completedFuture(it)) }
            enqueueDelete(CompletableFuture.completedFuture(delete))
        }
        return TemplateFixture(template(transport, batchEnabled), transport)
    }

    private fun template(
        transport: SqsBatchTransport,
        batchEnabled: Boolean = false,
    ): SqsBatchCoroutinesTemplate {
        val properties = templateProperties(enabled = batchEnabled)
        val resources = if (batchEnabled) {
            SqsBatchTransportResources(
                transport,
                AutoCloseable {},
                ScheduledThreadPoolExecutor(1),
            )
        } else {
            null
        }
        return SqsBatchCoroutinesTemplate(
            coordinator = SqsBatchCoordinator(properties, transport),
            resources = resources,
            properties = properties,
        )
    }
}

private class TemplateFixture(
    val template: SqsBatchCoroutinesTemplate,
    val transport: CoordinatorTestTransport,
)

internal fun templateProperties(
    enabled: Boolean = false,
    shutdownTimeout: Duration = Duration.ofMillis(50),
): SqsBatchProperties = SqsBatchProperties(
    enabled = enabled,
    maxBatchSize = 1,
    flushInterval = Duration.ofMillis(1),
    maxEntriesPerCall = 8,
    maxInFlightEntries = 2,
    schedulerThreads = 1,
    shutdownTimeout = shutdownTimeout,
)
