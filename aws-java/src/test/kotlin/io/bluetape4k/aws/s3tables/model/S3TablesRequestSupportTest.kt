package io.bluetape4k.aws.s3tables.model

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.s3tables.model.OpenTableFormat
import software.amazon.awssdk.services.s3tables.model.TableBucketType

class S3TablesRequestSupportTest {

    private companion object {
        const val BUCKET_ARN = "arn:aws:s3tables:ap-northeast-2:123456789012:bucket/test"
        const val TABLE_ARN = "$BUCKET_ARN/table/ns/orders"
    }

    @Test
    fun `create namespace and table preserve required fields and callback overrides`() {
        val namespace = createNamespaceRequestOf(BUCKET_ARN, listOf("analytics", "daily")) {
            namespace("override")
        }
        val table = createTableRequestOf(BUCKET_ARN, "analytics", "orders") {
            name("orders-v2")
        }

        namespace.tableBucketARN() shouldBeEqualTo BUCKET_ARN
        namespace.namespace() shouldBeEqualTo listOf("override")
        table.tableBucketARN() shouldBeEqualTo BUCKET_ARN
        table.namespace() shouldBeEqualTo "analytics"
        table.name() shouldBeEqualTo "orders-v2"
        table.format() shouldBeEqualTo OpenTableFormat.ICEBERG
    }

    @Test
    fun `create table rejects callback that removes required format`() {
        val error = assertFailsWith<IllegalArgumentException> {
            createTableRequestOf(BUCKET_ARN, "analytics", "orders") {
                format(null as OpenTableFormat?)
            }
        }

        error.message shouldBeEqualTo "format must not be null"
    }

    @Test
    fun `get table accepts exactly one selector`() {
        getTableRequestOf(tableArn = TABLE_ARN).tableArn() shouldBeEqualTo TABLE_ARN
        val byPath = getTableRequestOf(BUCKET_ARN, "analytics", "orders")
        byPath.tableBucketARN() shouldBeEqualTo BUCKET_ARN
        byPath.namespace() shouldBeEqualTo "analytics"
        byPath.name() shouldBeEqualTo "orders"

        assertFailsWith<IllegalArgumentException> { getTableRequestOf() }
        assertFailsWith<IllegalArgumentException> {
            getTableRequestOf(tableArn = TABLE_ARN) { name("orders") }
        }
        assertFailsWith<IllegalArgumentException> {
            getTableRequestOf(BUCKET_ARN, "analytics", "orders", tableArn = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            getTableRequestOf(tableArn = TABLE_ARN) { namespace(" ") }
        }
        assertFailsWith<IllegalArgumentException> {
            getTableRequestOf(tableBucketArn = BUCKET_ARN, namespace = "analytics")
        }
    }

    @Test
    fun `list requests preserve page filters and reject invalid page values`() {
        val buckets = listTableBucketsRequestOf(
            prefix = "prod-",
            continuationToken = "next",
            maxBuckets = 10,
            type = TableBucketType.CUSTOMER,
        )
        val namespaces = listNamespacesRequestOf(BUCKET_ARN, maxNamespaces = 20)
        val tables = listTablesRequestOf(BUCKET_ARN, "analytics", maxTables = 30)
        val tablesWithoutNamespace = listTablesRequestOf(BUCKET_ARN, maxTables = 40)

        buckets.prefix() shouldBeEqualTo "prod-"
        buckets.continuationToken() shouldBeEqualTo "next"
        buckets.maxBuckets() shouldBeEqualTo 10
        buckets.type() shouldBeEqualTo TableBucketType.CUSTOMER
        namespaces.maxNamespaces() shouldBeEqualTo 20
        tables.maxTables() shouldBeEqualTo 30
        tablesWithoutNamespace.namespace() shouldBeEqualTo null
        tablesWithoutNamespace.maxTables() shouldBeEqualTo 40
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
        true.shouldBeTrue()
    }

    @Test
    fun `callback cannot remove required identifiers`() {
        assertFailsWith<IllegalArgumentException> {
            getTableBucketRequestOf(BUCKET_ARN) { tableBucketARN(" ") }
        }
        assertFailsWith<IllegalArgumentException> {
            getNamespaceRequestOf(BUCKET_ARN, "analytics") { namespace(" ") }
        }
        assertFailsWith<IllegalArgumentException> {
            listTablesRequestOf(BUCKET_ARN, "analytics") { tableBucketARN(" ") }
        }
        assertFailsWith<IllegalArgumentException> {
            listTablesRequestOf(BUCKET_ARN) { namespace(" ") }
        }
        assertFailsWith<IllegalArgumentException> {
            deleteTableRequestOf(BUCKET_ARN, "analytics", "orders") { name(" ") }
        }
    }
}
