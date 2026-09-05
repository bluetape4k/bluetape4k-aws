@file:Suppress("DEPRECATION")

package io.bluetape4k.aws.kotlin.kinesis

import aws.sdk.kotlin.services.kinesis.KinesisClient
import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.kinesis.model.ShardIteratorType
import aws.sdk.kotlin.services.kinesis.model.StreamStatus
import io.bluetape4k.aws.kotlin.AbstractAwsTest.Companion.endpointUrl
import io.bluetape4k.aws.kotlin.AbstractAwsTest.Companion.region
import io.bluetape4k.aws.kotlin.kinesis.model.putRecordsRequestEntryOf
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.testcontainers.aws.FlociServer
import io.bluetape4k.testcontainers.aws.AwsEmulatorServer
import io.bluetape4k.testcontainers.aws.LocalStackServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.opentest4j.TestAbortedException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** 선택된 emulator의 Kinesis DryRun capability와 지원 응답의 write no-op을 검증합니다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Execution(ExecutionMode.SAME_THREAD)
class KinesisDryRunEmulatorTest : AbstractKotlinKinesisTest() {

    private val backend by lazy { validatedKinesisDryRunBackend(configuredAwsEmulatorName()) }
    private val backendVersion by lazy {
        when (backend) {
            "floci" -> FlociServer.TAG
            "localstack" -> LocalStackServer.TAG
            else -> "unknown"
        }
    }
    private val reportRows = linkedMapOf<KinesisDryRunOperation, KinesisDryRunCapabilityRow>()
    private val runNonce = Uuid.V7.nextIdAsString().replace("-", "").take(12)

    @BeforeAll
    fun resetCapabilityReport() {
        Files.deleteIfExists(rawReportPath())
        Files.deleteIfExists(validatedReportPath())
    }

    @AfterAll
    fun writeCapabilityReport() {
        val path = rawReportPath()
        Files.createDirectories(path.parent)
        val rows = KinesisDryRunOperation.entries.mapNotNull(reportRows::get)
        Files.writeString(path, rows.joinToString(prefix = "[\n", postfix = "\n]\n", separator = ",\n") { it.toJson() })
    }

    @Test
    @Order(1)
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    fun `PutRecord DryRun capability probe`() = runSuspendIO {
        runScenario(KinesisDryRunOperation.PUT_RECORD) { client, boundary, streamName, streamToken ->
            val marker = "$streamToken-put-record"
            val baselineMarker = seedBaselineMarker(client, boundary, streamName, streamToken)
            assertMarkersAbsent(client, boundary, streamName, setOf(marker))

            val decision = observeDryRun(KinesisDryRunOperation.PUT_RECORD, streamToken) {
                guarded(boundary) {
                    client.putRecord(
                        streamName = streamName,
                        partitionKey = "issue-620",
                        data = marker.encodeToByteArray(),
                        dryRun = true,
                    )
                }
            }

            val persistedMarkers = observePersistedMarkers(client, boundary, streamName, setOf(marker))
            assertBaselinePreserved(client, boundary, streamName, baselineMarker)
            resolveWriteObservation(
                operation = KinesisDryRunOperation.PUT_RECORD,
                streamToken = streamToken,
                decision = decision,
                persistedMarkers = persistedMarkers,
            )
        }
    }

    @Test
    @Order(2)
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    fun `PutRecords DryRun capability probe`() = runSuspendIO {
        runScenario(KinesisDryRunOperation.PUT_RECORDS) { client, boundary, streamName, streamToken ->
            val markers = setOf("$streamToken-put-records-a", "$streamToken-put-records-b")
            val baselineMarker = seedBaselineMarker(client, boundary, streamName, streamToken)
            assertMarkersAbsent(client, boundary, streamName, markers)

            val decision = observeDryRun(KinesisDryRunOperation.PUT_RECORDS, streamToken) {
                guarded(boundary) {
                    client.putRecords(
                        streamName = streamName,
                        entries = markers.mapIndexed { index, marker ->
                            putRecordsRequestEntryOf(
                                partitionKey = "issue-620-$index",
                                data = marker.encodeToByteArray(),
                            )
                        },
                        dryRun = true,
                    )
                }
            }

            val persistedMarkers = observePersistedMarkers(client, boundary, streamName, markers)
            assertBaselinePreserved(client, boundary, streamName, baselineMarker)
            resolveWriteObservation(
                operation = KinesisDryRunOperation.PUT_RECORDS,
                streamToken = streamToken,
                decision = decision,
                persistedMarkers = persistedMarkers,
            )
        }
    }

    @Test
    @Order(3)
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    fun `GetShardIterator DryRun capability probe`() = runSuspendIO {
        runScenario(KinesisDryRunOperation.GET_SHARD_ITERATOR) { client, boundary, streamName, streamToken ->
            val shardId = firstShardId(client, boundary, streamName)

            val decision = observeDryRun(KinesisDryRunOperation.GET_SHARD_ITERATOR, streamToken) {
                guarded(boundary) {
                    client.getShardIterator(
                        streamName = streamName,
                        shardId = shardId,
                        type = ShardIteratorType.TrimHorizon,
                        dryRun = true,
                    )
                }
            }
            resolveReadObservation(KinesisDryRunOperation.GET_SHARD_ITERATOR, streamToken, decision)
        }
    }

    @Test
    @Order(4)
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    fun `GetRecords DryRun capability probe uses a valid normal iterator`() = runSuspendIO {
        runScenario(KinesisDryRunOperation.GET_RECORDS) { client, boundary, streamName, streamToken ->
            val shardId = firstShardId(client, boundary, streamName)
            val iterator = withinKinesisOperationDeadline {
                guarded(boundary) {
                    client.getShardIterator(
                        streamName = streamName,
                        shardId = shardId,
                        type = ShardIteratorType.TrimHorizon,
                        dryRun = false,
                    ).shardIterator
                }
            }
            check(!iterator.isNullOrBlank()) { "normal shard iterator was blank" }

            val decision = observeDryRun(KinesisDryRunOperation.GET_RECORDS, streamToken) {
                guarded(boundary) {
                    client.getRecords(iterator, limit = 100, dryRun = true)
                }
            }
            resolveReadObservation(KinesisDryRunOperation.GET_RECORDS, streamToken, decision)
        }
    }

    @Suppress("ThrowsCount")
    private suspend fun runScenario(
        operation: KinesisDryRunOperation,
        body: suspend (KinesisClient, KinesisDryRunTestBoundary, String, String) -> Unit,
    ) {
        val streamToken = "i620-${operation.token}-$runNonce"
        try {
            val server = localStackServer
            val boundary = KinesisDryRunTestBoundary(
                endpoint = server.endpointUrl,
                accessKey = server.awsAccessKey,
                secretKey = server.awsSecretKey,
            )
            verifyKinesisDryRunTestBoundary(boundary)

            withTimeout(120.seconds) {
                withVerifiedKinesisClient(server, boundary) { client ->
                    withOwnedKinesisStream(
                        nameFactory = { attempt -> "$streamToken-$attempt" },
                        describe = { streamName ->
                            withinKinesisOperationDeadline {
                                guarded(boundary) { client.describeStream(streamName) }
                            }
                        },
                        create = { streamName ->
                            withinKinesisOperationDeadline {
                                guarded(boundary) { client.createStream(streamName, shardCount = 1) }
                            }
                        },
                        delete = { streamName -> guarded(boundary) { client.deleteStream(streamName) } },
                    ) { streamName ->
                        waitUntilActive(client, boundary, streamName)
                        body(client, boundary, streamName, streamToken)
                        record(
                            operation,
                            KinesisDryRunStatus.SUPPORTED,
                            KinesisDryRunReason.DRY_RUN_ACCEPTED,
                            streamToken,
                        )
                    }
                }
            }
        } catch (aborted: TestAbortedException) {
            if (aborted.suppressed.isNotEmpty()) {
                record(operation, KinesisDryRunStatus.FAILED, KinesisDryRunReason.UNEXPECTED_FAILURE, streamToken)
                throw AssertionError(safeEvidence(operation, KinesisDryRunReason.UNEXPECTED_FAILURE, streamToken))
            }
            throw aborted
        } catch (failure: Throwable) {
            val row = reportRows[operation] ?: classifyKinesisDryRunFailure(failure).let { decision ->
                record(operation, decision.status, decision.reason, streamToken)
                reportRows.getValue(operation)
            }
            throw AssertionError(row.assumptionMessage())
        }
    }

    private suspend fun <T> withVerifiedKinesisClient(
        server: AwsEmulatorServer,
        boundary: KinesisDryRunTestBoundary,
        block: suspend (KinesisClient) -> T,
    ): T {
        verifyKinesisDryRunTestBoundary(boundary)
        val accessKey = requireNotNull(boundary.accessKey)
        val secretKey = requireNotNull(boundary.secretKey)
        val credentials = StaticCredentialsProvider {
            accessKeyId = accessKey
            secretAccessKey = secretKey
        }
        return withKinesisClient(
            endpointUrl = requireNotNull(boundary.endpoint),
            region = server.region,
            credentialsProvider = credentials,
            block = block,
        )
    }

    private suspend fun observeDryRun(
        operation: KinesisDryRunOperation,
        streamToken: String,
        call: suspend () -> Unit,
    ): KinesisDryRunDecision {
        val failure = try {
            withinKinesisOperationDeadline { call() }
            null
        } catch (failure: Throwable) {
            failure
        }
        val decision = classifyKinesisDryRunFailure(failure)
        when (decision.status) {
            KinesisDryRunStatus.SUPPORTED -> return decision
            KinesisDryRunStatus.UNSUPPORTED -> {
                record(operation, decision.status, decision.reason, streamToken)
                throw TestAbortedException(safeEvidence(operation, decision.reason, streamToken))
            }
            KinesisDryRunStatus.FAILED -> {
                if (decision.reason == KinesisDryRunReason.NORMAL_RESPONSE) return decision
                record(operation, decision.status, decision.reason, streamToken)
                throw AssertionError(safeEvidence(operation, decision.reason, streamToken))
            }
        }
    }

    private fun resolveReadObservation(
        operation: KinesisDryRunOperation,
        streamToken: String,
        decision: KinesisDryRunDecision,
    ) {
        if (decision.status == KinesisDryRunStatus.SUPPORTED) return
        check(decision.reason == KinesisDryRunReason.NORMAL_RESPONSE)
        abortUnsupported(operation, KinesisDryRunReason.DRY_RUN_IGNORED_RESPONSE, streamToken)
    }

    private fun resolveWriteObservation(
        operation: KinesisDryRunOperation,
        streamToken: String,
        decision: KinesisDryRunDecision,
        persistedMarkers: Set<String>,
    ) {
        if (decision.status == KinesisDryRunStatus.SUPPORTED) {
            check(persistedMarkers.isEmpty()) { "DryRun marker was persisted" }
            return
        }
        check(decision.reason == KinesisDryRunReason.NORMAL_RESPONSE)
        val reason = if (persistedMarkers.isEmpty()) {
            KinesisDryRunReason.DRY_RUN_IGNORED_RESPONSE
        } else {
            KinesisDryRunReason.DRY_RUN_IGNORED_WRITE
        }
        abortUnsupported(operation, reason, streamToken)
    }

    private fun abortUnsupported(
        operation: KinesisDryRunOperation,
        reason: KinesisDryRunReason,
        streamToken: String,
    ): Nothing {
        record(operation, KinesisDryRunStatus.UNSUPPORTED, reason, streamToken)
        throw TestAbortedException(safeEvidence(operation, reason, streamToken))
    }

    private suspend fun waitUntilActive(
        client: KinesisClient,
        boundary: KinesisDryRunTestBoundary,
        streamName: String,
    ) = withinKinesisOperationDeadline {
        val pollInterval = boundedKinesisPollInterval(500.milliseconds)
        while (true) {
            val status = guarded(boundary) {
                client.describeStream(streamName).streamDescription?.streamStatus
            }
            if (status == StreamStatus.Active) return@withinKinesisOperationDeadline
            delay(pollInterval)
        }
    }

    private suspend fun firstShardId(
        client: KinesisClient,
        boundary: KinesisDryRunTestBoundary,
        streamName: String,
    ): String = withinKinesisOperationDeadline {
        guarded(boundary) {
            client.describeStream(streamName).streamDescription?.shards.orEmpty().firstOrNull()?.shardId
        }.orEmpty().also { check(it.isNotBlank()) { "stream shard id was blank" } }
    }

    private suspend fun assertMarkersAbsent(
        client: KinesisClient,
        boundary: KinesisDryRunTestBoundary,
        streamName: String,
        markers: Set<String>,
    ) {
        check(readPersistedMarkers(client, boundary, streamName, markers).isEmpty()) {
            "stream contained a marker before DryRun"
        }
    }

    private suspend fun seedBaselineMarker(
        client: KinesisClient,
        boundary: KinesisDryRunTestBoundary,
        streamName: String,
        streamToken: String,
    ): String {
        val baselineMarker = "$streamToken-baseline"
        withinKinesisOperationDeadline {
            guarded(boundary) {
                client.putRecord(
                    streamName = streamName,
                    partitionKey = "issue-620-baseline",
                    data = baselineMarker.encodeToByteArray(),
                    dryRun = false,
                )
            }
        }
        awaitKinesisCondition {
            readPersistedMarkers(client, boundary, streamName, setOf(baselineMarker))
                .takeIf { baselineMarker in it }
        }
        return baselineMarker
    }

    private suspend fun assertBaselinePreserved(
        client: KinesisClient,
        boundary: KinesisDryRunTestBoundary,
        streamName: String,
        baselineMarker: String,
    ) {
        val persisted = readPersistedMarkers(client, boundary, streamName, setOf(baselineMarker))
        check(baselineMarker in persisted) { "baseline marker disappeared after DryRun" }
    }

    private suspend fun observePersistedMarkers(
        client: KinesisClient,
        boundary: KinesisDryRunTestBoundary,
        streamName: String,
        markers: Set<String>,
    ): Set<String> = observeKinesisConditionUntilDeadline(
        pollInterval = 200.milliseconds,
    ) {
            val persisted = readPersistedMarkers(client, boundary, streamName, markers)
            persisted.takeIf { it.isNotEmpty() }
        }.orEmpty()

    private suspend fun readPersistedMarkers(
        client: KinesisClient,
        boundary: KinesisDryRunTestBoundary,
        streamName: String,
        markers: Set<String>,
    ): Set<String> = withinKinesisOperationDeadline {
        val shardId = firstShardId(client, boundary, streamName)
        val iterator = guarded(boundary) {
            client.getShardIterator(
                streamName = streamName,
                shardId = shardId,
                type = ShardIteratorType.TrimHorizon,
                dryRun = false,
            ).shardIterator
        }
        check(!iterator.isNullOrBlank()) { "normal shard iterator was blank" }
        val records = guarded(boundary) {
            client.getRecords(iterator, limit = 100, dryRun = false).records
        }
        records.map { it.data.decodeToString() }.filterTo(mutableSetOf()) { it in markers }
    }

    private inline fun <T> guarded(boundary: KinesisDryRunTestBoundary, block: () -> T): T {
        verifyKinesisDryRunTestBoundary(boundary)
        return block()
    }

    @Synchronized
    private fun record(
        operation: KinesisDryRunOperation,
        status: KinesisDryRunStatus,
        reason: KinesisDryRunReason,
        streamToken: String,
    ) {
        reportRows[operation] = sanitizedKinesisDryRunEvidence(
            backend = backend,
            backendVersion = backendVersion,
            operation = operation,
            decision = KinesisDryRunDecision(status, reason),
            streamToken = streamToken,
        )
    }

    private fun safeEvidence(
        operation: KinesisDryRunOperation,
        reason: KinesisDryRunReason,
        streamToken: String,
    ): String = listOf(
        "backend=$backend",
        "version=$backendVersion",
        "operation=${operation.wireName}",
        "reason=${reason.wireValue}",
        "streamToken=$streamToken",
    ).joinToString(" ")

    private fun rawReportPath(): Path = reportDirectory().resolve("capability-$backend.json")

    private fun validatedReportPath(): Path = reportDirectory().resolve("capability-$backend.validated.json")

    private fun reportDirectory(): Path {
        val userDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val moduleDirectory = if (userDirectory.fileName.toString() == "aws-kotlin") {
            userDirectory
        } else {
            userDirectory.resolve("aws-kotlin")
        }
        return moduleDirectory.resolve("build/reports/kinesis-dry-run")
    }

    private fun KinesisDryRunCapabilityRow.toJson(): String =
        """  {"schemaVersion":$schemaVersion,"backend":"$backend","backendVersion":"$backendVersion","operation":"${operation.wireName}","status":"${status.wireValue}","sanitizedReason":"${sanitizedReason.wireValue}","streamToken":"$streamToken"}"""
}
