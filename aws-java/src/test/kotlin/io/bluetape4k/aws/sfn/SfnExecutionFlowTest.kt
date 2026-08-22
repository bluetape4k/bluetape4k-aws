package io.bluetape4k.aws.sfn

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.sfn.SfnAsyncClient
import software.amazon.awssdk.services.sfn.model.DescribeExecutionRequest
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse
import software.amazon.awssdk.services.sfn.model.ExecutionStatus
import software.amazon.awssdk.services.sfn.model.IncludedData
import software.amazon.awssdk.services.sfn.model.StopExecutionRequest
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SfnExecutionFlowTest {

    private companion object {
        const val EXECUTION_ARN = "arn:aws:states:ap-northeast-2:123456789012:execution:orders:order-1"
    }

    @Test
    fun `running 뒤 terminal raw response를 방출하고 끝난다`() = runTest {
        val running = response(ExecutionStatus.RUNNING)
        val succeeded = response(ExecutionStatus.SUCCEEDED)
        val client = mockk<SfnAsyncClient>()
        every { client.describeExecution(any<DescribeExecutionRequest>()) } returnsMany listOf(
            CompletableFuture.completedFuture(running),
            CompletableFuture.completedFuture(succeeded),
        )

        client.describeExecutionFlow(EXECUTION_ARN).toList() shouldBeEqualTo listOf(running, succeeded)
        verify(exactly = 2) { client.describeExecution(any<DescribeExecutionRequest>()) }
    }

    @Test
    fun `첫 조회는 즉시 실행되고 1초 뒤 다음 조회를 실행한다`() = runTest {
        val running = response(ExecutionStatus.RUNNING)
        val succeeded = response(ExecutionStatus.SUCCEEDED)
        val second = CompletableFuture<DescribeExecutionResponse>()
        val client = mockk<SfnAsyncClient>()
        every { client.describeExecution(any<DescribeExecutionRequest>()) } returnsMany listOf(
            CompletableFuture.completedFuture(running),
            second,
        )

        val collector = launch { client.describeExecutionFlow(EXECUTION_ARN).toList() }
        runCurrent()
        verify(exactly = 1) { client.describeExecution(any<DescribeExecutionRequest>()) }

        advanceTimeBy(999.milliseconds)
        runCurrent()
        verify(exactly = 1) { client.describeExecution(any<DescribeExecutionRequest>()) }

        advanceTimeBy(1.milliseconds)
        runCurrent()
        verify(exactly = 2) { client.describeExecution(any<DescribeExecutionRequest>()) }
        second.complete(succeeded)
        collector.join()
    }

    @Test
    fun `take one은 추가 조회 없이 첫 응답에서 끝난다`() = runTest {
        val running = response(ExecutionStatus.RUNNING)
        val client = mockk<SfnAsyncClient>()
        every { client.describeExecution(any<DescribeExecutionRequest>()) } returns
            CompletableFuture.completedFuture(running)

        client.describeExecutionFlow(EXECUTION_ARN).take(1).toList() shouldBeEqualTo listOf(running)
        verify(exactly = 1) { client.describeExecution(any<DescribeExecutionRequest>()) }
        verify(exactly = 0) { client.stopExecution(any<StopExecutionRequest>()) }
    }

    @Test
    fun `collector가 느려도 한 번에 하나의 조회만 유지한다`() = runTest {
        val running = response(ExecutionStatus.RUNNING)
        val succeeded = response(ExecutionStatus.SUCCEEDED)
        val release = CompletableDeferred<Unit>()
        val client = mockk<SfnAsyncClient>()
        every { client.describeExecution(any<DescribeExecutionRequest>()) } returnsMany listOf(
            CompletableFuture.completedFuture(running),
            CompletableFuture.completedFuture(succeeded),
        )
        val seen = mutableListOf<DescribeExecutionResponse>()

        val collector = launch {
            client.describeExecutionFlow(EXECUTION_ARN).collect {
                seen += it
                if (it === running) release.await()
            }
        }
        runCurrent()
        verify(exactly = 1) { client.describeExecution(any<DescribeExecutionRequest>()) }
        seen shouldBeEqualTo listOf(running)

        release.complete(Unit)
        runCurrent()
        verify(exactly = 1) { client.describeExecution(any<DescribeExecutionRequest>()) }

        advanceTimeBy(1.seconds)
        runCurrent()
        collector.join()
        seen shouldBeEqualTo listOf(running, succeeded)
    }

    @Test
    fun `취소하면 현재 future만 취소하고 client와 execution을 건드리지 않는다`() = runTest {
        val future = CompletableFuture<DescribeExecutionResponse>()
        val client = mockk<SfnAsyncClient>(relaxed = true)
        every { client.describeExecution(any<DescribeExecutionRequest>()) } returns future

        val collector = launch { client.describeExecutionFlow(EXECUTION_ARN).toList() }
        runCurrent()
        collector.cancelAndJoin()

        future.isCancelled.shouldBeTrue()
        verify(exactly = 1) { client.describeExecution(any<DescribeExecutionRequest>()) }
        verify(exactly = 0) { client.stopExecution(any<StopExecutionRequest>()) }
        verify(exactly = 0) { client.close() }
    }

    @Test
    fun `pending redrive는 terminal raw response로 끝난다`() = runTest {
        val pendingRedrive = response(ExecutionStatus.PENDING_REDRIVE)
        val client = mockk<SfnAsyncClient>()
        every { client.describeExecution(any<DescribeExecutionRequest>()) } returns
            CompletableFuture.completedFuture(pendingRedrive)

        client.describeExecutionFlow(EXECUTION_ARN).toList() shouldBeEqualTo listOf(pendingRedrive)
        verify(exactly = 1) { client.describeExecution(any<DescribeExecutionRequest>()) }
    }

    @Test
    fun `null status는 방출하지 않고 고정 예외 메시지를 반환한다`() = runTest {
        val response = response(null)
        val client = mockk<SfnAsyncClient>()
        every { client.describeExecution(any<DescribeExecutionRequest>()) } returns
            CompletableFuture.completedFuture(response)
        val emissions = mutableListOf<DescribeExecutionResponse>()

        val error = assertFailsWith<IllegalStateException> {
            client.describeExecutionFlow(EXECUTION_ARN).collect { emissions += it }
        }

        error.message shouldBeEqualTo "Unsupported Step Functions execution status: <null>"
        emissions shouldBeEqualTo emptyList()
        verify(exactly = 1) { client.describeExecution(any<DescribeExecutionRequest>()) }
        verify(exactly = 0) { client.stopExecution(any<StopExecutionRequest>()) }
    }

    @Test
    fun `unknown status는 statusAsString을 예외 메시지에 사용한다`() = runTest {
        val response = DescribeExecutionResponse.builder().status("FUTURE_STATUS").build()
        val client = mockk<SfnAsyncClient>()
        every { client.describeExecution(any<DescribeExecutionRequest>()) } returns
            CompletableFuture.completedFuture(response)
        val emissions = mutableListOf<DescribeExecutionResponse>()

        val error = assertFailsWith<IllegalStateException> {
            client.describeExecutionFlow(EXECUTION_ARN).collect { emissions += it }
        }

        error.message shouldBeEqualTo "Unsupported Step Functions execution status: FUTURE_STATUS"
        emissions shouldBeEqualTo emptyList()
        response.status() shouldBeSameInstanceAs ExecutionStatus.UNKNOWN_TO_SDK_VERSION
    }

    @Test
    fun `poll interval은 1초 미만과 무한 duration을 거부한다`() {
        assertFailsWith<IllegalArgumentException> { SfnExecutionPollingOptions(999.milliseconds) }
        assertFailsWith<IllegalArgumentException> { SfnExecutionPollingOptions(Duration.INFINITE) }
        assertFailsWith<IllegalArgumentException> { SfnExecutionPollingOptions(-Duration.INFINITE) }
        SfnExecutionPollingOptions(1.seconds).pollInterval shouldBeEqualTo 1.seconds
    }

    @Test
    fun `request overload는 모든 조회에서 같은 immutable request를 전달한다`() = runTest {
        val request = DescribeExecutionRequest.builder()
            .executionArn(EXECUTION_ARN)
            .includedData(IncludedData.ALL_DATA)
            .build()
        val running = response(ExecutionStatus.RUNNING)
        val succeeded = response(ExecutionStatus.SUCCEEDED)
        val requests = mutableListOf<DescribeExecutionRequest>()
        val client = mockk<SfnAsyncClient>()
        every { client.describeExecution(capture(requests)) } returnsMany listOf(
            CompletableFuture.completedFuture(running),
            CompletableFuture.completedFuture(succeeded),
        )

        client.describeExecutionFlow(request).toList()

        requests.size shouldBeEqualTo 2
        requests[0] shouldBeSameInstanceAs request
        requests[1] shouldBeSameInstanceAs request
        requests.all { it.includedData() == IncludedData.ALL_DATA }.shouldBeTrue()
    }

    @Test
    fun `같은 cold flow의 collector는 각각 독립적으로 조회한다`() = runTest {
        val running = response(ExecutionStatus.RUNNING)
        val succeeded = response(ExecutionStatus.SUCCEEDED)
        val client = mockk<SfnAsyncClient>()
        every { client.describeExecution(any<DescribeExecutionRequest>()) } returnsMany listOf(
            CompletableFuture.completedFuture(running),
            CompletableFuture.completedFuture(succeeded),
            CompletableFuture.completedFuture(running),
            CompletableFuture.completedFuture(succeeded),
        )
        val flow = client.describeExecutionFlow(EXECUTION_ARN)

        flow.toList() shouldBeEqualTo listOf(running, succeeded)
        flow.toList() shouldBeEqualTo listOf(running, succeeded)
        verify(exactly = 4) { client.describeExecution(any<DescribeExecutionRequest>()) }
    }

    private fun response(status: ExecutionStatus?): DescribeExecutionResponse =
        DescribeExecutionResponse.builder().apply {
            status?.let { status(it) }
        }.build()
}
