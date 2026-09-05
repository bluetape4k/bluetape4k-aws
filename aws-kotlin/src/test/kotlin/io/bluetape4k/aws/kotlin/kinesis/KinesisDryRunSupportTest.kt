package io.bluetape4k.aws.kotlin.kinesis

import aws.sdk.kotlin.services.kinesis.model.DryRunOperationException
import aws.sdk.kotlin.services.kinesis.model.ResourceInUseException
import aws.sdk.kotlin.services.kinesis.model.ResourceNotFoundException
import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.ServiceErrorMetadata
import aws.smithy.kotlin.runtime.ServiceException
import aws.smithy.kotlin.runtime.http.HttpStatusCode
import aws.smithy.kotlin.runtime.http.response.HttpResponse
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(InternalApi::class)
class KinesisDryRunSupportTest {

    @Test
    fun `collision retries without deleting pre-existing stream`() = runTest {
        val names = ArrayDeque(listOf("existing", "owned"))
        val describeCalls = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val created = mutableListOf<String>()

        val result = withOwnedKinesisStream(
            nameFactory = { _ -> names.removeFirst() },
            describe = { name ->
                describeCalls += name
                if (name == "existing") Unit else throw ResourceNotFoundException { message = "missing" }
            },
            create = { name -> created += name },
            delete = { name -> deleted += name },
        ) { it }

        result shouldBeEqualTo "owned"
        describeCalls shouldBeEqualTo listOf("existing", "owned")
        created shouldBeEqualTo listOf("owned")
        deleted shouldBeEqualTo listOf("owned")
    }

    @Test
    fun `ResourceInUse collision is never treated as owned`() = runTest {
        val names = ArrayDeque(listOf("busy", "owned"))
        val deleted = mutableListOf<String>()

        val result = withOwnedKinesisStream(
            nameFactory = { _ -> names.removeFirst() },
            describe = { name ->
                if (name == "busy") throw ResourceInUseException { message = "already active" }
                throw ResourceNotFoundException { message = "missing" }
            },
            create = { },
            delete = { deleted += it },
        ) { it }

        result shouldBeEqualTo "owned"
        deleted shouldBeEqualTo listOf("owned")
    }

    @Test
    fun `ambiguous create failure cleans only the registered name`() = runTest {
        val createFailure = IllegalStateException("response lost")
        val deleted = mutableListOf<String>()

        val actual = assertFailsWith<IllegalStateException> {
            withOwnedKinesisStream(
                nameFactory = { _ -> "ambiguous" },
                describe = { throw ResourceNotFoundException { message = "missing" } },
                create = { throw createFailure },
                delete = { deleted += it },
            ) { error("unreachable") }
        }

        actual shouldBeSameInstanceAs createFailure
        deleted shouldBeEqualTo listOf("ambiguous")
    }

    @Test
    fun `ResourceInUse from create retries without deleting the colliding name`() = runTest {
        val names = ArrayDeque(listOf("busy", "owned"))
        val created = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val createCalls = AtomicInteger(0)

        val result = withOwnedKinesisStream(
            nameFactory = { _ -> names.removeFirst() },
            describe = { throw ResourceNotFoundException { message = "missing" } },
            create = { name ->
                created += name
                if (createCalls.getAndIncrement() == 0) throw ResourceInUseException { message = "race" }
            },
            delete = { deleted += it },
        ) { it }

        result shouldBeEqualTo "owned"
        created shouldBeEqualTo listOf("busy", "owned")
        deleted shouldBeEqualTo listOf("owned")
    }

    @Test
    fun `three collisions exhaust allocation without deleting foreign streams`() = runTest {
        val deleted = mutableListOf<String>()
        assertFailsWith<IllegalStateException> {
            withOwnedKinesisStream(
                nameFactory = { attempt -> "foreign-$attempt" },
                describe = { Unit },
                create = { error("must not create") },
                delete = { deleted += it },
            ) { error("unreachable") }
        }
        deleted shouldBeEqualTo emptyList()
    }

    @Test
    fun `primary body failure survives successful cleanup`() = runTest {
        val bodyFailure = IllegalArgumentException("body failed")
        val actual = assertFailsWith<IllegalArgumentException> {
            withOwnedKinesisStream(
                nameFactory = { _ -> "owned" },
                describe = { throw ResourceNotFoundException { message = "missing" } },
                create = { },
                delete = { },
            ) { throw bodyFailure }
        }
        actual shouldBeSameInstanceAs bodyFailure
    }

    @Test
    fun `cancellation identity survives non cancellable cleanup`() = runTest {
        val cancellation = CancellationException("caller cancelled")
        var deleted = false
        val actual = assertFailsWith<CancellationException> {
            withOwnedKinesisStream(
                nameFactory = { _ -> "owned" },
                describe = { throw ResourceNotFoundException { message = "missing" } },
                create = { },
                delete = { deleted = true },
            ) {
                throw cancellation
            }
        }

        deleted.shouldBeTrue()
        actual shouldBeSameInstanceAs cancellation
    }

    @Test
    fun `actual coroutine cancellation still runs bounded cleanup`() = runTest {
        val cancellation = CancellationException("caller cancelled")
        val cleanupCompleted = CompletableDeferred<Unit>()
        val observed = CompletableDeferred<Throwable>()
        val job = launch {
            try {
                withOwnedKinesisStream(
                    nameFactory = { _ -> "owned" },
                    describe = { throw ResourceNotFoundException { message = "missing" } },
                    create = { },
                    delete = {
                        delay(1)
                        cleanupCompleted.complete(Unit)
                    },
                ) {
                    awaitCancellation()
                }
            } catch (failure: Throwable) {
                observed.complete(failure)
            }
        }
        runCurrent()
        job.cancel(cancellation)
        job.join()

        cleanupCompleted.await()
        val observedFailure = observed.await()
        assertTrue(observedFailure is CancellationException)
        assertEquals(cancellation.message, observedFailure.message)
    }

    @Test
    fun `cleanup failure is suppressed on the primary failure`() = runTest {
        val primary = IllegalStateException("primary")
        val cleanupFailure = IllegalStateException("cleanup")
        val actual = assertFailsWith<IllegalStateException> {
            withOwnedKinesisStream(
                nameFactory = { _ -> "owned" },
                describe = { throw ResourceNotFoundException { message = "missing" } },
                create = { },
                delete = { throw cleanupFailure },
            ) { throw primary }
        }

        actual shouldBeSameInstanceAs primary
        actual.suppressed.single() shouldBeSameInstanceAs cleanupFailure
    }

    @Test
    fun `cleanup failure becomes primary when body succeeds`() = runTest {
        val cleanupFailure = IllegalStateException("cleanup")
        val actual = assertFailsWith<IllegalStateException> {
            withOwnedKinesisStream(
                nameFactory = { _ -> "owned" },
                describe = { throw ResourceNotFoundException { message = "missing" } },
                create = { },
                delete = { throw cleanupFailure },
            ) { "ok" }
        }
        actual shouldBeSameInstanceAs cleanupFailure
    }

    @Test
    fun `ResourceNotFound during cleanup is idempotent`() = runTest {
        withOwnedKinesisStream(
            nameFactory = { _ -> "owned" },
            describe = { throw ResourceNotFoundException { message = "missing" } },
            create = { },
            delete = { throw ResourceNotFoundException { message = "already gone" } },
        ) { "ok" } shouldBeEqualTo "ok"
    }

    @Test
    fun `cleanup timeout is suppressed without replacing body failure`() = runTest {
        val primary = IllegalStateException("primary")
        val actual = assertFailsWith<IllegalStateException> {
            withOwnedKinesisStream(
                nameFactory = { _ -> "owned" },
                describe = { throw ResourceNotFoundException { message = "missing" } },
                create = { },
                delete = { delay(10.seconds) },
                cleanupTimeout = 1.milliseconds,
            ) { throw primary }
        }

        actual shouldBeSameInstanceAs primary
        assertEquals(1, actual.suppressed.size)
    }

    @Test
    fun `preflight failure does not register an unowned name`() = runTest {
        val preflightFailure = IllegalStateException("access denied")
        val deleted = mutableListOf<String>()
        val actual = assertFailsWith<IllegalStateException> {
            withOwnedKinesisStream(
                nameFactory = { _ -> "foreign" },
                describe = { throw preflightFailure },
                create = { error("must not create") },
                delete = { deleted += it },
            ) { error("unreachable") }
        }

        actual shouldBeSameInstanceAs preflightFailure
        deleted shouldBeEqualTo emptyList()
    }

    @Test
    fun `classifier accepts only DryRunOperationException as supported`() {
        val decision = classifyKinesisDryRunFailure(DryRunOperationException { message = "accepted" })
        decision.status shouldBeEqualTo KinesisDryRunStatus.SUPPORTED
        decision.reason shouldBeEqualTo KinesisDryRunReason.DRY_RUN_ACCEPTED
    }

    @Test
    fun `classifier recognizes the closed unsupported set`() {
        val notImplemented = serviceException("NotImplemented", 501, "not implemented")
        val notImplementedException = serviceException("NotImplementedException", 501, "not implemented")
        val unknownMember = serviceException("SerializationException", 400, "DryRun unknown member")
        val unsupportedMember = serviceException("ValidationException", 400, "unsupported DryRun member")
        val invalidArgument = serviceException("InvalidArgumentException", 400, "DryRun unknown field")

        listOf(notImplemented, notImplementedException, unknownMember, unsupportedMember, invalidArgument)
            .forEach { failure ->
                val decision = classifyKinesisDryRunFailure(failure)
                decision.status shouldBeEqualTo KinesisDryRunStatus.UNSUPPORTED
                val expectedReason = KinesisDryRunReason.NOT_IMPLEMENTED.takeIf {
                    failure.sdkErrorMetadata.errorCode in setOf("NotImplemented", "NotImplementedException")
                } ?: KinesisDryRunReason.UNKNOWN_DRY_RUN_MEMBER
                decision.reason shouldBeEqualTo expectedReason
            }
    }

    @Test
    fun `classifier never skips access denied transport timeout assertion or normal response`() {
        val failures = listOf<Throwable?>(
            serviceException("AccessDenied", 403, "DryRun denied"),
            serviceException("AccessDenied", 403, "forbidden"),
            IOException("connection failed"),
            java.util.concurrent.TimeoutException("timed out"),
            CancellationException("timed out waiting for operation"),
            AssertionError("assertion failed"),
            null,
        )

        failures.forEach { failure ->
            val decision = classifyKinesisDryRunFailure(failure)
            decision.status shouldBeEqualTo KinesisDryRunStatus.FAILED
            decision.reason shouldBeEqualTo when (failure) {
                null -> KinesisDryRunReason.NORMAL_RESPONSE
                is AssertionError -> KinesisDryRunReason.ASSERTION_FAILURE
                is java.util.concurrent.TimeoutException -> KinesisDryRunReason.TIMEOUT
                is CancellationException -> KinesisDryRunReason.TIMEOUT
                is IOException -> KinesisDryRunReason.ENDPOINT_FAILURE
                else -> KinesisDryRunReason.ACCESS_DENIED
            }
        }
    }

    @Test
    fun `classifier rejects lookalike unsupported responses`() {
        listOf(
            serviceException("SerializationException", 400, "serialization failed"),
            serviceException("ValidationException", 400, "DryRun is valid"),
            serviceException("InvalidArgumentException", 500, "DryRun unknown member"),
            serviceException("NotImplemented", 400, "not implemented"),
        ).forEach { failure ->
            classifyKinesisDryRunFailure(failure).status
                .shouldBeEqualTo(KinesisDryRunStatus.FAILED)
        }
    }

    @Test
    fun `sanitizer exposes only bounded allow list fields`() {
        val row = sanitizedKinesisDryRunEvidence(
            backend = "floci",
            backendVersion = "1.6.0",
            operation = KinesisDryRunOperation.PUT_RECORD,
            failure = serviceException(
                "AccessDenied",
                403,
                "Authorization payload body access-key-id secret-access-key session-token http://user@host",
            ),
            streamToken = "dryrun-abc123",
        )

        row.backend shouldBeEqualTo "floci"
        row.backendVersion shouldBeEqualTo "1.6.0"
        row.status shouldBeEqualTo KinesisDryRunStatus.FAILED
        row.sanitizedReason shouldBeEqualTo KinesisDryRunReason.ACCESS_DENIED
        val rendered = row.assumptionMessage()
        rendered.contains("Authorization").shouldBeFalse()
        rendered.contains("payload").shouldBeFalse()
        rendered.contains("access-key-id").shouldBeFalse()
        rendered.contains("session-token").shouldBeFalse()
        rendered.length shouldBeLessThan 512
    }

    @Test
    fun `sanitizer rejects unapproved backend and stream token`() {
        assertThrows(IllegalArgumentException::class.java) {
            sanitizedKinesisDryRunEvidence(
                backend = "aws",
                backendVersion = "1",
                operation = KinesisDryRunOperation.PUT_RECORD,
                failure = null,
                streamToken = "dryrun-token",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            sanitizedKinesisDryRunEvidence(
                backend = "floci",
                backendVersion = "1",
                operation = KinesisDryRunOperation.PUT_RECORD,
                failure = null,
                streamToken = "raw secret token",
            )
        }
    }

    @Test
    fun `backend name rejects report path traversal before path construction`() {
        validatedKinesisDryRunBackend("floci") shouldBeEqualTo "floci"
        validatedKinesisDryRunBackend("localstack") shouldBeEqualTo "localstack"

        listOf("../../outside", "floci/../outside", "aws").forEach { backend ->
            assertThrows(IllegalArgumentException::class.java) {
                validatedKinesisDryRunBackend(backend)
            }
        }
    }

    @Test
    fun `boundary rejects non-loopback endpoint or non static fake credentials before call`() {
        val targets = listOf(
            KinesisDryRunTestBoundary(null, "test", "test"),
            KinesisDryRunTestBoundary(Url.parse("http://127.0.0.1:4566"), null, null),
            KinesisDryRunTestBoundary(Url.parse("https://kinesis.us-east-1.amazonaws.com"), "test", "test"),
            KinesisDryRunTestBoundary(Url.parse("http://user@127.0.0.1:4566"), "test", "test"),
            KinesisDryRunTestBoundary(Url.parse("http://127.0.0.1:4566"), "ambient", "ambient"),
            KinesisDryRunTestBoundary(Url.parse("http://example.test:4566"), "test", "test"),
        )

        targets.forEach { boundary ->
            val calls = AtomicInteger(0)
            assertThrows(IllegalArgumentException::class.java) {
                verifyKinesisDryRunTestBoundary(boundary)
                calls.incrementAndGet()
            }
            calls.get() shouldBeEqualTo 0
        }

        assertDoesNotThrow {
            verifyKinesisDryRunTestBoundary(
                KinesisDryRunTestBoundary(Url.parse("http://127.0.0.1:4566"), "test", "test"),
            )
        }
    }

    @Test
    fun `operation deadline bounds polling and clamps interval`() = runTest {
        val intervals = mutableListOf<kotlin.time.Duration>()
        var now = 0L
        var attempts = 0
        val result = awaitKinesisCondition(
            timeout = 2.seconds,
            pollInterval = 5.seconds,
            now = { now },
            delay = { interval ->
                intervals += interval
                now += interval.inWholeNanoseconds
            },
        ) {
            attempts += 1
            "ready".takeIf { attempts == 2 }
        }

        result shouldBeEqualTo "ready"
        intervals.single() shouldBeEqualTo 500.milliseconds
    }

    @Test
    fun `observation consumes the full fake deadline before returning absent`() = runTest {
        val intervals = mutableListOf<kotlin.time.Duration>()
        var now = 0L

        val result = observeKinesisConditionUntilDeadline<String>(
            timeout = 1.seconds,
            pollInterval = 500.milliseconds,
            now = { now },
            delay = { interval ->
                intervals += interval
                now += interval.inWholeNanoseconds
            },
        ) { null }

        result shouldBeEqualTo null
        intervals shouldBeEqualTo listOf(500.milliseconds, 500.milliseconds)
        now shouldBeEqualTo 1.seconds.inWholeNanoseconds
    }

    @Test
    fun `operation deadline rejects a never ready condition`() = runTest {
        var now = 0L
        val actual = assertFailsWith<TimeoutException> {
            awaitKinesisCondition(
                timeout = 1.seconds,
                pollInterval = 500.milliseconds,
                now = { now },
                delay = { interval -> now += interval.inWholeNanoseconds },
            ) { null }
        }

        assertNotNull(actual)
    }

    @Test
    fun `observation propagates a poll that times out in flight`() = runTest {
        val actual = assertFailsWith<TimeoutException> {
            observeKinesisConditionUntilDeadline<String>(timeout = 10.milliseconds) {
                awaitCancellation()
            }
        }

        assertNotNull(actual)
    }

    @Test
    fun `within operation deadline preserves block result`() = runTest {
        withinKinesisOperationDeadline(timeout = 1.seconds) { "ok" } shouldBeEqualTo "ok"
    }

    @Test
    fun `operation deadline rejects budgets above thirty seconds`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            withinKinesisOperationDeadline(timeout = 31.seconds) { "unreachable" }
        }
    }

    @Test
    fun `stream tokens are bounded and generated`() {
        val token = newKinesisDryRunStreamToken("run-123")
        token.startsWith("run-123-").shouldBeTrue()
        token.length shouldBeLessThan 128
    }

    private fun serviceException(
        errorCode: String,
        statusCode: Int,
        message: String,
    ): ServiceException = ServiceException(message).also { exception ->
        exception.sdkErrorMetadata.attributes[ServiceErrorMetadata.ErrorCode] = errorCode
        exception.sdkErrorMetadata.attributes[ServiceErrorMetadata.ProtocolResponse] =
            HttpResponse(status = HttpStatusCode.fromValue(statusCode))
    }

    private infix fun Int.shouldBeLessThan(other: Int) {
        if (this >= other) error("Expected $this < $other")
    }
}
