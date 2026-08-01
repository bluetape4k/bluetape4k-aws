package io.bluetape4k.aws.dynamodb.schema

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable
import software.amazon.awssdk.enhanced.dynamodb.model.CreateTableEnhancedRequest
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class DynamoDbAsyncTableCreatorTest {

    private val table = mockk<DynamoDbAsyncTable<String>>()
    private val request = CreateTableEnhancedRequest.builder().build()
    private val creator = DynamoDbAsyncTableCreator()

    @Test
    fun `direct ResourceInUse exception is idempotent success`() = runTest {
        every { table.tableName() } returns "orders"
        every { table.createTable(request) } returns failedFuture(ResourceInUseException.builder().message("exists").build())

        creator.tryCreateAsyncTable(table, request)
    }

    @Test
    fun `wrapped ResourceInUse exception is idempotent success`() = runTest {
        every { table.tableName() } returns "orders"
        every { table.createTable(request) } returns failedFuture(
            CompletionException(ResourceInUseException.builder().message("exists").build())
        )

        creator.tryCreateAsyncTable(table, request)
    }

    @Test
    fun `cancellation is preserved`() = runTest {
        every { table.tableName() } returns "orders"
        every { table.createTable(request) } returns failedFuture(CancellationException("cancelled"))

        assertFailsWith<CancellationException> {
            creator.tryCreateAsyncTable(table, request)
        }
    }

    @Test
    fun `unexpected failure is wrapped with complete table diagnostic`() = runTest {
        every { table.tableName() } returns "orders"
        every { table.createTable(request) } returns failedFuture(IllegalStateException("boom"))

        val error = assertFailsWith<io.bluetape4k.aws.exceptions.AwsBluetapeException> {
            creator.tryCreateAsyncTable(table, request)
        }

        error.message shouldBeEqualTo "Fail to create table [orders]"
    }

    private fun <T> failedFuture(error: Throwable): CompletableFuture<T> =
        CompletableFuture<T>().also { it.completeExceptionally(error) }
}
