package io.bluetape4k.aws.s3tables

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.s3tables.S3TablesAsyncClient
import software.amazon.awssdk.services.s3tables.S3TablesClient
import software.amazon.awssdk.services.s3tables.model.CreateNamespaceRequest
import software.amazon.awssdk.services.s3tables.model.CreateNamespaceResponse
import software.amazon.awssdk.services.s3tables.model.CreateTableBucketRequest
import software.amazon.awssdk.services.s3tables.model.CreateTableBucketResponse
import software.amazon.awssdk.services.s3tables.model.CreateTableRequest
import software.amazon.awssdk.services.s3tables.model.CreateTableResponse
import software.amazon.awssdk.services.s3tables.model.DeleteNamespaceRequest
import software.amazon.awssdk.services.s3tables.model.DeleteNamespaceResponse
import software.amazon.awssdk.services.s3tables.model.DeleteTableBucketRequest
import software.amazon.awssdk.services.s3tables.model.DeleteTableBucketResponse
import software.amazon.awssdk.services.s3tables.model.DeleteTableRequest
import software.amazon.awssdk.services.s3tables.model.DeleteTableResponse
import software.amazon.awssdk.services.s3tables.model.GetNamespaceRequest
import software.amazon.awssdk.services.s3tables.model.GetNamespaceResponse
import software.amazon.awssdk.services.s3tables.model.GetTableBucketRequest
import software.amazon.awssdk.services.s3tables.model.GetTableBucketResponse
import software.amazon.awssdk.services.s3tables.model.GetTableRequest
import software.amazon.awssdk.services.s3tables.model.GetTableResponse
import software.amazon.awssdk.services.s3tables.model.ListNamespacesRequest
import software.amazon.awssdk.services.s3tables.model.ListNamespacesResponse
import software.amazon.awssdk.services.s3tables.model.ListTableBucketsRequest
import software.amazon.awssdk.services.s3tables.model.ListTableBucketsResponse
import software.amazon.awssdk.services.s3tables.model.ListTablesRequest
import software.amazon.awssdk.services.s3tables.model.ListTablesResponse
import software.amazon.awssdk.services.s3tables.model.TableBucketType
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

class S3TablesExtensionsTest {

    private companion object {
        const val BUCKET_ARN = "arn:aws:s3tables:ap-northeast-2:123456789012:bucket/test"
    }

    @Test
    fun `sync surface delegates all twelve operations with exact request fields`() {
        val client = mockk<S3TablesClient>()
        val createBucket = CreateTableBucketResponse.builder().build()
        val listBuckets = ListTableBucketsResponse.builder().build()
        val getBucket = GetTableBucketResponse.builder().build()
        val deleteBucket = DeleteTableBucketResponse.builder().build()
        val createNamespace = CreateNamespaceResponse.builder().build()
        val listNamespaces = ListNamespacesResponse.builder().build()
        val getNamespace = GetNamespaceResponse.builder().build()
        val deleteNamespace = DeleteNamespaceResponse.builder().build()
        val createTable = CreateTableResponse.builder().build()
        val listTables = ListTablesResponse.builder().build()
        val getTable = GetTableResponse.builder().build()
        val deleteTable = DeleteTableResponse.builder().build()
        every { client.createTableBucket(any<CreateTableBucketRequest>()) } returns createBucket
        every { client.listTableBuckets(any<ListTableBucketsRequest>()) } returns listBuckets
        every { client.getTableBucket(any<GetTableBucketRequest>()) } returns getBucket
        every { client.deleteTableBucket(any<DeleteTableBucketRequest>()) } returns deleteBucket
        every { client.createNamespace(any<CreateNamespaceRequest>()) } returns createNamespace
        every { client.listNamespaces(any<ListNamespacesRequest>()) } returns listNamespaces
        every { client.getNamespace(any<GetNamespaceRequest>()) } returns getNamespace
        every { client.deleteNamespace(any<DeleteNamespaceRequest>()) } returns deleteNamespace
        every { client.createTable(any<CreateTableRequest>()) } returns createTable
        every { client.listTables(any<ListTablesRequest>()) } returns listTables
        every { client.getTable(any<GetTableRequest>()) } returns getTable
        every { client.deleteTable(any<DeleteTableRequest>()) } returns deleteTable

        client.createTableBucket("test") shouldBeSameInstanceAs createBucket
        client.listTableBuckets(maxBuckets = 10, type = TableBucketType.CUSTOMER) shouldBeSameInstanceAs listBuckets
        client.getTableBucket(BUCKET_ARN) shouldBeSameInstanceAs getBucket
        client.deleteTableBucket(BUCKET_ARN) shouldBeSameInstanceAs deleteBucket
        client.createNamespace(BUCKET_ARN, listOf("analytics")) shouldBeSameInstanceAs createNamespace
        client.listNamespaces(BUCKET_ARN, maxNamespaces = 10) shouldBeSameInstanceAs listNamespaces
        client.getNamespace(BUCKET_ARN, "analytics") shouldBeSameInstanceAs getNamespace
        client.deleteNamespace(BUCKET_ARN, "analytics") shouldBeSameInstanceAs deleteNamespace
        client.createTable(BUCKET_ARN, "analytics", "orders") shouldBeSameInstanceAs createTable
        client.listTables(BUCKET_ARN, "analytics", maxTables = 10) shouldBeSameInstanceAs listTables
        client.getTable(tableArn = "$BUCKET_ARN/table/analytics/orders") shouldBeSameInstanceAs getTable
        client.deleteTable(BUCKET_ARN, "analytics", "orders", versionToken = "v1") shouldBeSameInstanceAs deleteTable

        verifySyncBucketOperations(client)
        verifySyncNamespaceOperations(client)
        verifySyncTableOperations(client)
    }

    private fun verifySyncBucketOperations(client: S3TablesClient) {
        verify(exactly = 1) {
            client.createTableBucket(match<CreateTableBucketRequest> { request -> request.name() == "test" })
        }
        verify(exactly = 1) {
            client.listTableBuckets(
                match<ListTableBucketsRequest> { request ->
                    request.maxBuckets() == 10 && request.type() == TableBucketType.CUSTOMER
                },
            )
        }
        verify(exactly = 1) {
            client.getTableBucket(match<GetTableBucketRequest> { request -> request.tableBucketARN() == BUCKET_ARN })
        }
        verify(exactly = 1) {
            client.deleteTableBucket(
                match<DeleteTableBucketRequest> { request -> request.tableBucketARN() == BUCKET_ARN },
            )
        }
    }

    private fun verifySyncNamespaceOperations(client: S3TablesClient) {
        verify(exactly = 1) {
            client.createNamespace(
                match<CreateNamespaceRequest> { request ->
                    request.tableBucketARN() == BUCKET_ARN && request.namespace() == listOf("analytics")
                },
            )
        }
        verify(exactly = 1) {
            client.listNamespaces(
                match<ListNamespacesRequest> { request ->
                    request.tableBucketARN() == BUCKET_ARN && request.maxNamespaces() == 10
                },
            )
        }
        verify(exactly = 1) {
            client.getNamespace(
                match<GetNamespaceRequest> { request ->
                    request.tableBucketARN() == BUCKET_ARN && request.namespace() == "analytics"
                },
            )
        }
        verify(exactly = 1) {
            client.deleteNamespace(
                match<DeleteNamespaceRequest> { request ->
                    request.tableBucketARN() == BUCKET_ARN && request.namespace() == "analytics"
                },
            )
        }
    }

    private fun verifySyncTableOperations(client: S3TablesClient) {
        verify(exactly = 1) {
            client.createTable(
                match<CreateTableRequest> { request ->
                    request.tableBucketARN() == BUCKET_ARN &&
                        request.namespace() == "analytics" &&
                        request.name() == "orders" &&
                        request.formatAsString() == "ICEBERG"
                },
            )
        }
        verify(exactly = 1) {
            client.listTables(
                match<ListTablesRequest> { request ->
                    request.tableBucketARN() == BUCKET_ARN &&
                        request.namespace() == "analytics" &&
                        request.maxTables() == 10
                },
            )
        }
        verify(exactly = 1) {
            client.getTable(
                match<GetTableRequest> { request ->
                    request.tableArn() != null && request.tableBucketARN() == null
                },
            )
        }
        verify(exactly = 1) {
            client.deleteTable(
                match<DeleteTableRequest> { request ->
                    request.tableBucketARN() == BUCKET_ARN &&
                        request.namespace() == "analytics" &&
                        request.name() == "orders" &&
                        request.versionToken() == "v1"
                },
            )
        }
    }

    @Test
    fun `async and coroutine preserve responses`() = runTest {
        val client = mockk<S3TablesAsyncClient>()
        val expected = stubAsyncClient(client)

        verifyFutureResponses(client, expected)
        verifyCoroutineResponses(client, expected)
        verifyAsyncBucketRequests(client)
        verifyAsyncNamespaceRequests(client)
        verifyAsyncTableRequests(client)
    }

    private data class AsyncResponses(
        val createBucket: CreateTableBucketResponse,
        val listBuckets: ListTableBucketsResponse,
        val getBucket: GetTableBucketResponse,
        val deleteBucket: DeleteTableBucketResponse,
        val createNamespace: CreateNamespaceResponse,
        val listNamespaces: ListNamespacesResponse,
        val getNamespace: GetNamespaceResponse,
        val deleteNamespace: DeleteNamespaceResponse,
        val createTable: CreateTableResponse,
        val listTables: ListTablesResponse,
        val getTable: GetTableResponse,
        val deleteTable: DeleteTableResponse,
    )

    private fun stubAsyncClient(client: S3TablesAsyncClient): AsyncResponses {
        val expected = AsyncResponses(
            createBucket = CreateTableBucketResponse.builder().build(),
            listBuckets = ListTableBucketsResponse.builder().build(),
            getBucket = GetTableBucketResponse.builder().build(),
            deleteBucket = DeleteTableBucketResponse.builder().build(),
            createNamespace = CreateNamespaceResponse.builder().build(),
            listNamespaces = ListNamespacesResponse.builder().build(),
            getNamespace = GetNamespaceResponse.builder().build(),
            deleteNamespace = DeleteNamespaceResponse.builder().build(),
            createTable = CreateTableResponse.builder().build(),
            listTables = ListTablesResponse.builder().build(),
            getTable = GetTableResponse.builder().build(),
            deleteTable = DeleteTableResponse.builder().build(),
        )
        every { client.createTableBucket(any<CreateTableBucketRequest>()) } returns
            CompletableFuture.completedFuture(expected.createBucket)
        every { client.listTableBuckets(any<ListTableBucketsRequest>()) } returns
            CompletableFuture.completedFuture(expected.listBuckets)
        every { client.getTableBucket(any<GetTableBucketRequest>()) } returns
            CompletableFuture.completedFuture(expected.getBucket)
        every { client.deleteTableBucket(any<DeleteTableBucketRequest>()) } returns
            CompletableFuture.completedFuture(expected.deleteBucket)
        every { client.createNamespace(any<CreateNamespaceRequest>()) } returns
            CompletableFuture.completedFuture(expected.createNamespace)
        every { client.listNamespaces(any<ListNamespacesRequest>()) } returns
            CompletableFuture.completedFuture(expected.listNamespaces)
        every { client.getNamespace(any<GetNamespaceRequest>()) } returns
            CompletableFuture.completedFuture(expected.getNamespace)
        every { client.deleteNamespace(any<DeleteNamespaceRequest>()) } returns
            CompletableFuture.completedFuture(expected.deleteNamespace)
        every { client.createTable(any<CreateTableRequest>()) } returns
            CompletableFuture.completedFuture(expected.createTable)
        every { client.listTables(any<ListTablesRequest>()) } returns
            CompletableFuture.completedFuture(expected.listTables)
        every { client.getTable(any<GetTableRequest>()) } returns
            CompletableFuture.completedFuture(expected.getTable)
        every { client.deleteTable(any<DeleteTableRequest>()) } returns
            CompletableFuture.completedFuture(expected.deleteTable)
        return expected
    }

    private fun verifyFutureResponses(client: S3TablesAsyncClient, expected: AsyncResponses) {
        client.createTableBucketAsync("test").get() shouldBeSameInstanceAs expected.createBucket
        client.listTableBucketsAsync(maxBuckets = 10, type = TableBucketType.CUSTOMER).get() shouldBeSameInstanceAs
            expected.listBuckets
        client.getTableBucketAsync(BUCKET_ARN).get() shouldBeSameInstanceAs expected.getBucket
        client.deleteTableBucketAsync(BUCKET_ARN).get() shouldBeSameInstanceAs expected.deleteBucket
        client.createNamespaceAsync(BUCKET_ARN, listOf("analytics")).get() shouldBeSameInstanceAs
            expected.createNamespace
        client.listNamespacesAsync(BUCKET_ARN, maxNamespaces = 10).get() shouldBeSameInstanceAs
            expected.listNamespaces
        client.getNamespaceAsync(BUCKET_ARN, "analytics").get() shouldBeSameInstanceAs expected.getNamespace
        client.deleteNamespaceAsync(BUCKET_ARN, "analytics").get() shouldBeSameInstanceAs expected.deleteNamespace
        client.createTableAsync(BUCKET_ARN, "analytics", "orders").get() shouldBeSameInstanceAs expected.createTable
        client.listTablesAsync(BUCKET_ARN, "analytics", maxTables = 10).get() shouldBeSameInstanceAs expected.listTables
        client.getTableAsync(tableArn = "$BUCKET_ARN/table/analytics/orders").get() shouldBeSameInstanceAs
            expected.getTable
        client.deleteTableAsync(BUCKET_ARN, "analytics", "orders", versionToken = "v1").get() shouldBeSameInstanceAs
            expected.deleteTable
    }

    private suspend fun verifyCoroutineResponses(client: S3TablesAsyncClient, expected: AsyncResponses) {
        client.createTableBucket("test") shouldBeSameInstanceAs expected.createBucket
        client.listTableBuckets(maxBuckets = 10, type = TableBucketType.CUSTOMER) shouldBeSameInstanceAs
            expected.listBuckets
        client.getTableBucket(BUCKET_ARN) shouldBeSameInstanceAs expected.getBucket
        client.deleteTableBucket(BUCKET_ARN) shouldBeSameInstanceAs expected.deleteBucket
        client.createNamespace(BUCKET_ARN, listOf("analytics")) shouldBeSameInstanceAs expected.createNamespace
        client.listNamespaces(BUCKET_ARN, maxNamespaces = 10) shouldBeSameInstanceAs expected.listNamespaces
        client.getNamespace(BUCKET_ARN, "analytics") shouldBeSameInstanceAs expected.getNamespace
        client.deleteNamespace(BUCKET_ARN, "analytics") shouldBeSameInstanceAs expected.deleteNamespace
        client.createTable(BUCKET_ARN, "analytics", "orders") shouldBeSameInstanceAs expected.createTable
        client.listTables(BUCKET_ARN, "analytics", maxTables = 10) shouldBeSameInstanceAs expected.listTables
        client.getTable(tableArn = "$BUCKET_ARN/table/analytics/orders") shouldBeSameInstanceAs expected.getTable
        client.deleteTable(BUCKET_ARN, "analytics", "orders", versionToken = "v1") shouldBeSameInstanceAs
            expected.deleteTable
    }

    private fun verifyAsyncBucketRequests(client: S3TablesAsyncClient) {
        verify(exactly = 2) {
            client.createTableBucket(match<CreateTableBucketRequest> { it.name() == "test" })
        }
        verify(exactly = 2) {
            client.listTableBuckets(
                match<ListTableBucketsRequest> {
                    it.maxBuckets() == 10 && it.type() == TableBucketType.CUSTOMER
                },
            )
        }
        verify(exactly = 2) {
            client.getTableBucket(match<GetTableBucketRequest> { it.tableBucketARN() == BUCKET_ARN })
        }
        verify(exactly = 2) {
            client.deleteTableBucket(match<DeleteTableBucketRequest> { it.tableBucketARN() == BUCKET_ARN })
        }
    }

    private fun verifyAsyncNamespaceRequests(client: S3TablesAsyncClient) {
        verify(exactly = 2) {
            client.createNamespace(
                match<CreateNamespaceRequest> {
                    it.tableBucketARN() == BUCKET_ARN && it.namespace() == listOf("analytics")
                },
            )
        }
        verify(exactly = 2) {
            client.listNamespaces(
                match<ListNamespacesRequest> {
                    it.tableBucketARN() == BUCKET_ARN && it.maxNamespaces() == 10
                },
            )
        }
        verify(exactly = 2) {
            client.getNamespace(
                match<GetNamespaceRequest> {
                    it.tableBucketARN() == BUCKET_ARN && it.namespace() == "analytics"
                },
            )
        }
        verify(exactly = 2) {
            client.deleteNamespace(
                match<DeleteNamespaceRequest> {
                    it.tableBucketARN() == BUCKET_ARN && it.namespace() == "analytics"
                },
            )
        }
    }

    private fun verifyAsyncTableRequests(client: S3TablesAsyncClient) {
        verify(exactly = 2) {
            client.createTable(
                match<CreateTableRequest> {
                    it.tableBucketARN() == BUCKET_ARN &&
                        it.namespace() == "analytics" &&
                        it.name() == "orders" &&
                        it.formatAsString() == "ICEBERG"
                },
            )
        }
        verify(exactly = 2) {
            client.listTables(
                match<ListTablesRequest> {
                    it.tableBucketARN() == BUCKET_ARN &&
                        it.namespace() == "analytics" &&
                        it.maxTables() == 10
                },
            )
        }
        verify(exactly = 2) {
            client.getTable(
                match<GetTableRequest> {
                    it.tableArn() != null && it.tableBucketARN() == null
                },
            )
        }
        verify(exactly = 2) {
            client.deleteTable(
                match<DeleteTableRequest> {
                    it.tableBucketARN() == BUCKET_ARN &&
                        it.namespace() == "analytics" &&
                        it.name() == "orders" &&
                        it.versionToken() == "v1"
                },
            )
        }
    }

    @Test
    fun `coroutine await rethrows cancellation`() = runTest {
        val client = mockk<S3TablesAsyncClient>()
        val cancelled = CompletableFuture<GetTableResponse>()
        cancelled.completeExceptionally(CancellationException("cancelled"))
        every { client.getTable(any<GetTableRequest>()) } returns cancelled

        assertFailsWith<CancellationException> {
            client.getTable(tableArn = "$BUCKET_ARN/table/analytics/orders")
        }
    }
}
