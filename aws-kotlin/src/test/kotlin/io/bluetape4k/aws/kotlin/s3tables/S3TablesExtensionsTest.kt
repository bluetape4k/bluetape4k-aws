package io.bluetape4k.aws.kotlin.s3tables

import aws.sdk.kotlin.services.s3tables.S3TablesClient
import aws.sdk.kotlin.services.s3tables.model.CreateNamespaceRequest
import aws.sdk.kotlin.services.s3tables.model.CreateNamespaceResponse
import aws.sdk.kotlin.services.s3tables.model.CreateTableBucketRequest
import aws.sdk.kotlin.services.s3tables.model.CreateTableBucketResponse
import aws.sdk.kotlin.services.s3tables.model.CreateTableRequest
import aws.sdk.kotlin.services.s3tables.model.CreateTableResponse
import aws.sdk.kotlin.services.s3tables.model.DeleteNamespaceRequest
import aws.sdk.kotlin.services.s3tables.model.DeleteNamespaceResponse
import aws.sdk.kotlin.services.s3tables.model.DeleteTableBucketRequest
import aws.sdk.kotlin.services.s3tables.model.DeleteTableBucketResponse
import aws.sdk.kotlin.services.s3tables.model.DeleteTableRequest
import aws.sdk.kotlin.services.s3tables.model.DeleteTableResponse
import aws.sdk.kotlin.services.s3tables.model.GetNamespaceRequest
import aws.sdk.kotlin.services.s3tables.model.GetNamespaceResponse
import aws.sdk.kotlin.services.s3tables.model.GetTableBucketRequest
import aws.sdk.kotlin.services.s3tables.model.GetTableBucketResponse
import aws.sdk.kotlin.services.s3tables.model.GetTableRequest
import aws.sdk.kotlin.services.s3tables.model.GetTableResponse
import aws.sdk.kotlin.services.s3tables.model.ListNamespacesRequest
import aws.sdk.kotlin.services.s3tables.model.ListNamespacesResponse
import aws.sdk.kotlin.services.s3tables.model.ListTableBucketsRequest
import aws.sdk.kotlin.services.s3tables.model.ListTableBucketsResponse
import aws.sdk.kotlin.services.s3tables.model.ListTablesRequest
import aws.sdk.kotlin.services.s3tables.model.ListTablesResponse
import aws.sdk.kotlin.services.s3tables.model.TableBucketType
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class S3TablesExtensionsTest {

    private companion object {
        const val BUCKET_ARN = "arn:aws:s3tables:ap-northeast-2:123456789012:bucket/test"
    }

    @Test
    fun `suspend surface delegates all twelve operations with exact request fields`() = runTest {
        val client = mockk<S3TablesClient>()
        val createBucket = mockk<CreateTableBucketResponse>(relaxed = true)
        val listBuckets = mockk<ListTableBucketsResponse>(relaxed = true)
        val getBucket = mockk<GetTableBucketResponse>(relaxed = true)
        val deleteBucket = mockk<DeleteTableBucketResponse>(relaxed = true)
        val createNamespace = mockk<CreateNamespaceResponse>(relaxed = true)
        val listNamespaces = mockk<ListNamespacesResponse>(relaxed = true)
        val getNamespace = mockk<GetNamespaceResponse>(relaxed = true)
        val deleteNamespace = mockk<DeleteNamespaceResponse>(relaxed = true)
        val createTable = mockk<CreateTableResponse>(relaxed = true)
        val listTables = mockk<ListTablesResponse>(relaxed = true)
        val getTable = mockk<GetTableResponse>(relaxed = true)
        val deleteTable = mockk<DeleteTableResponse>(relaxed = true)
        coEvery { client.createTableBucket(any<CreateTableBucketRequest>()) } returns createBucket
        coEvery { client.listTableBuckets(any<ListTableBucketsRequest>()) } returns listBuckets
        coEvery { client.getTableBucket(any<GetTableBucketRequest>()) } returns getBucket
        coEvery { client.deleteTableBucket(any<DeleteTableBucketRequest>()) } returns deleteBucket
        coEvery { client.createNamespace(any<CreateNamespaceRequest>()) } returns createNamespace
        coEvery { client.listNamespaces(any<ListNamespacesRequest>()) } returns listNamespaces
        coEvery { client.getNamespace(any<GetNamespaceRequest>()) } returns getNamespace
        coEvery { client.deleteNamespace(any<DeleteNamespaceRequest>()) } returns deleteNamespace
        coEvery { client.createTable(any<CreateTableRequest>()) } returns createTable
        coEvery { client.listTables(any<ListTablesRequest>()) } returns listTables
        coEvery { client.getTable(any<GetTableRequest>()) } returns getTable
        coEvery { client.deleteTable(any<DeleteTableRequest>()) } returns deleteTable

        client.createTableBucket("test") shouldBeSameInstanceAs createBucket
        client.listTableBuckets(maxBuckets = 10, type = TableBucketType.Customer) shouldBeSameInstanceAs listBuckets
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

        verifyBucketRequests(client)
        verifyNamespaceRequests(client)
        verifyTableRequests(client)
    }

    private fun verifyBucketRequests(client: S3TablesClient) {
        coVerify(exactly = 1) { client.createTableBucket(match { it.name == "test" }) }
        coVerify(exactly = 1) {
            client.listTableBuckets(match { it.maxBuckets == 10 && it.type == TableBucketType.Customer })
        }
        coVerify(exactly = 1) { client.getTableBucket(match { it.tableBucketArn == BUCKET_ARN }) }
        coVerify(exactly = 1) { client.deleteTableBucket(match { it.tableBucketArn == BUCKET_ARN }) }
    }

    private fun verifyNamespaceRequests(client: S3TablesClient) {
        coVerify(exactly = 1) {
            client.createNamespace(match { it.tableBucketArn == BUCKET_ARN && it.namespace == listOf("analytics") })
        }
        coVerify(exactly = 1) {
            client.listNamespaces(match { it.tableBucketArn == BUCKET_ARN && it.maxNamespaces == 10 })
        }
        coVerify(exactly = 1) {
            client.getNamespace(match { it.tableBucketArn == BUCKET_ARN && it.namespace == "analytics" })
        }
        coVerify(exactly = 1) {
            client.deleteNamespace(match { it.tableBucketArn == BUCKET_ARN && it.namespace == "analytics" })
        }
    }

    private fun verifyTableRequests(client: S3TablesClient) {
        coVerify(exactly = 1) {
            client.createTable(
                match {
                    it.tableBucketArn == BUCKET_ARN &&
                        it.namespace == "analytics" &&
                        it.name == "orders" &&
                        it.format?.value == "ICEBERG"
                },
            )
        }
        coVerify(exactly = 1) {
            client.listTables(
                match {
                    it.tableBucketArn == BUCKET_ARN &&
                        it.namespace == "analytics" &&
                        it.maxTables == 10
                },
            )
        }
        coVerify(exactly = 1) {
            client.getTable(match { it.tableArn != null && it.tableBucketArn == null })
        }
        coVerify(exactly = 1) {
            client.deleteTable(
                match {
                    it.tableBucketArn == BUCKET_ARN &&
                        it.namespace == "analytics" &&
                        it.name == "orders" &&
                        it.versionToken == "v1"
                },
            )
        }
    }
}
