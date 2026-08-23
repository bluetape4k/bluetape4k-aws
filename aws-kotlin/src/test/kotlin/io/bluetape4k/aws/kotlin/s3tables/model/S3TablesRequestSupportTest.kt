package io.bluetape4k.aws.kotlin.s3tables.model

import aws.sdk.kotlin.services.s3tables.model.OpenTableFormat
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class S3TablesRequestSupportTest {

    private companion object {
        const val BUCKET_ARN = "arn:aws:s3tables:ap-northeast-2:123456789012:bucket/test"
        const val TABLE_ARN = "$BUCKET_ARN/table/ns/orders"
    }

    @Test
    fun `create namespace and table preserve required fields and callback overrides`() {
        val namespace = createNamespaceRequestOf(BUCKET_ARN, listOf("analytics", "daily")) {
            namespace = listOf("override")
        }
        val table = createTableRequestOf(BUCKET_ARN, "analytics", "orders") {
            name = "orders-v2"
        }

        namespace.tableBucketArn shouldBeEqualTo BUCKET_ARN
        namespace.namespace shouldBeEqualTo listOf("override")
        table.tableBucketArn shouldBeEqualTo BUCKET_ARN
        table.namespace shouldBeEqualTo "analytics"
        table.name shouldBeEqualTo "orders-v2"
        table.format shouldBeEqualTo OpenTableFormat.Iceberg
    }

    @Test
    fun `create table rejects callback that removes required format`() {
        val error = assertFailsWith<IllegalArgumentException> {
            createTableRequestOf(BUCKET_ARN, "analytics", "orders") {
                format = null
            }
        }

        error.message shouldBeEqualTo "format must not be null"
    }

    @Test
    fun `get table accepts exactly one selector`() {
        getTableRequestOf(tableArn = TABLE_ARN).tableArn shouldBeEqualTo TABLE_ARN
        getTableRequestOf(BUCKET_ARN, "analytics", "orders").name shouldBeEqualTo "orders"
        assertFailsWith<IllegalArgumentException> { getTableRequestOf() }
        assertFailsWith<IllegalArgumentException> {
            getTableRequestOf(tableArn = TABLE_ARN) { name = "orders" }
        }
    }

    @Test
    fun `list requests preserve page fields and reject invalid values`() {
        val buckets = listTableBucketsRequestOf(prefix = "prod-", continuationToken = "next", maxBuckets = 10)
        val namespaces = listNamespacesRequestOf(BUCKET_ARN, maxNamespaces = 20)
        val tables = listTablesRequestOf(BUCKET_ARN, "analytics", maxTables = 30)

        buckets.prefix shouldBeEqualTo "prod-"
        buckets.continuationToken shouldBeEqualTo "next"
        buckets.maxBuckets shouldBeEqualTo 10
        namespaces.maxNamespaces shouldBeEqualTo 20
        tables.maxTables shouldBeEqualTo 30
        assertFailsWith<IllegalArgumentException> { listTablesRequestOf(BUCKET_ARN, "analytics", maxTables = 0) }
        assertFailsWith<IllegalArgumentException> { listNamespacesRequestOf(BUCKET_ARN, continuationToken = " ") }
    }

    @Test
    fun `all required identifiers reject blank values`() {
        assertFailsWith<IllegalArgumentException> { createTableBucketRequestOf(" ") }
        assertFailsWith<IllegalArgumentException> { getTableBucketRequestOf(" ") }
        assertFailsWith<IllegalArgumentException> { createNamespaceRequestOf(BUCKET_ARN, listOf("", "daily")) }
        assertFailsWith<IllegalArgumentException> { getNamespaceRequestOf(BUCKET_ARN, " ") }
        assertFailsWith<IllegalArgumentException> { createTableRequestOf(BUCKET_ARN, "analytics", " ") }
        assertFailsWith<IllegalArgumentException> { deleteTableRequestOf(BUCKET_ARN, "analytics", " ") }
    }

    @Test
    fun `callback cannot remove required identifiers`() {
        assertFailsWith<IllegalArgumentException> {
            getTableBucketRequestOf(BUCKET_ARN) { tableBucketArn = " " }
        }
        assertFailsWith<IllegalArgumentException> {
            getNamespaceRequestOf(BUCKET_ARN, "analytics") { namespace = " " }
        }
        assertFailsWith<IllegalArgumentException> {
            listTablesRequestOf(BUCKET_ARN, "analytics") { tableBucketArn = " " }
        }
        assertFailsWith<IllegalArgumentException> {
            deleteTableRequestOf(BUCKET_ARN, "analytics", "orders") { name = " " }
        }
    }
}
