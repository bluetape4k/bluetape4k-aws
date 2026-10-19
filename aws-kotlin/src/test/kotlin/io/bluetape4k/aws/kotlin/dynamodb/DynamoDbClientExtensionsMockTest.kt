package io.bluetape4k.aws.kotlin.dynamodb

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.DescribeTableRequest
import aws.sdk.kotlin.services.dynamodb.model.DescribeTableResponse
import aws.sdk.kotlin.services.dynamodb.model.ResourceNotFoundException
import aws.sdk.kotlin.services.dynamodb.model.TableDescription
import aws.sdk.kotlin.services.dynamodb.model.TableStatus
import aws.sdk.kotlin.services.dynamodb.model.ThrottlingException
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DynamoDbClientExtensionsMockTest {

    companion object: KLoggingChannel()

    private val client = mockk<DynamoDbClient>()

    @BeforeEach
    fun setup() {
        clearMocks(client)
    }

    @Test
    fun `getTableStatus returns null for missing tables`() = runSuspendIO {
        coEvery {
            client.describeTable(any<DescribeTableRequest>())
        } throws ResourceNotFoundException { message = "missing table" }

        val result = client.getTableStatus("missing-table")

        result.shouldBeNull()
        coVerify(exactly = 1) { client.describeTable(any<DescribeTableRequest>()) }
    }

    @Test
    fun `getTableStatus propagates retryable describe failures`() = runSuspendIO {
        coEvery {
            client.describeTable(any<DescribeTableRequest>())
        } throws ThrottlingException { message = "throttled" }

        assertFailsWith<ThrottlingException> {
            client.getTableStatus("orders")
        }

        coVerify(exactly = 1) { client.describeTable(any<DescribeTableRequest>()) }
    }

    @Test
    fun `waitForTableReady propagates retryable describe failures`() = runSuspendIO {
        coEvery {
            client.describeTable(any<DescribeTableRequest>())
        } throws ThrottlingException { message = "throttled" }

        assertFailsWith<ThrottlingException> {
            client.waitForTableReady("orders")
        }

        coVerify(exactly = 1) { client.describeTable(any<DescribeTableRequest>()) }
    }

    @Test
    fun `waitForTableReady completes only after table becomes active`() = runSuspendIO {
        coEvery {
            client.describeTable(any<DescribeTableRequest>())
        } returnsMany listOf(
            describeTableResponse(TableStatus.Creating),
            describeTableResponse(TableStatus.Active),
        )

        client.waitForTableReady("orders")

        coVerify(exactly = 2) { client.describeTable(any<DescribeTableRequest>()) }
    }

    @Test
    fun `waitForTableReady does not treat null status as ready`() = runSuspendIO {
        coEvery {
            client.describeTable(any<DescribeTableRequest>())
        } returnsMany listOf(
            DescribeTableResponse {},
            describeTableResponse(TableStatus.Active),
        )

        client.waitForTableReady("orders")

        coVerify(exactly = 2) { client.describeTable(any<DescribeTableRequest>()) }
    }

    @Test
    fun `waitForTableReady applies caller timeout`() = runSuspendIO {
        coEvery {
            client.describeTable(any<DescribeTableRequest>())
        } coAnswers {
            awaitCancellation()
        }

        val error = assertFailsWith<TimeoutCancellationException> {
            withTimeout(2.seconds) {
                client.waitForTableReady("orders", 25.milliseconds)
            }
        }

        error.message shouldContain "25 ms"
        coVerify(exactly = 1) { client.describeTable(any<DescribeTableRequest>()) }
    }

    private fun describeTableResponse(status: TableStatus): DescribeTableResponse =
        DescribeTableResponse {
            table = TableDescription {
                tableStatus = status
            }
        }
}
