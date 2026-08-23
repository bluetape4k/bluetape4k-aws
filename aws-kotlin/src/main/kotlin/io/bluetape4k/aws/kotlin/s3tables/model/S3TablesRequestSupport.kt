@file:Suppress("TooManyFunctions")

package io.bluetape4k.aws.kotlin.s3tables.model

import aws.sdk.kotlin.services.s3tables.model.CreateNamespaceRequest
import aws.sdk.kotlin.services.s3tables.model.CreateTableBucketRequest
import aws.sdk.kotlin.services.s3tables.model.CreateTableRequest
import aws.sdk.kotlin.services.s3tables.model.DeleteNamespaceRequest
import aws.sdk.kotlin.services.s3tables.model.DeleteTableBucketRequest
import aws.sdk.kotlin.services.s3tables.model.DeleteTableRequest
import aws.sdk.kotlin.services.s3tables.model.GetNamespaceRequest
import aws.sdk.kotlin.services.s3tables.model.GetTableBucketRequest
import aws.sdk.kotlin.services.s3tables.model.GetTableRequest
import aws.sdk.kotlin.services.s3tables.model.ListNamespacesRequest
import aws.sdk.kotlin.services.s3tables.model.ListTableBucketsRequest
import aws.sdk.kotlin.services.s3tables.model.ListTablesRequest
import aws.sdk.kotlin.services.s3tables.model.OpenTableFormat
import aws.sdk.kotlin.services.s3tables.model.TableBucketType

fun createTableBucketRequestOf(
    name: String,
    builder: CreateTableBucketRequest.Builder.() -> Unit = {},
): CreateTableBucketRequest = CreateTableBucketRequest {
    this.name = name
    builder()
}.also { it.name.requireNotBlank("name") }

fun listTableBucketsRequestOf(
    prefix: String? = null,
    continuationToken: String? = null,
    maxBuckets: Int? = null,
    type: TableBucketType? = null,
    builder: ListTableBucketsRequest.Builder.() -> Unit = {},
): ListTableBucketsRequest = ListTableBucketsRequest {
    this.prefix = prefix
    this.continuationToken = continuationToken
    this.maxBuckets = maxBuckets
    this.type = type
    builder()
}.also { it.validateList(it.maxBuckets, "maxBuckets") }

fun getTableBucketRequestOf(
    tableBucketArn: String,
    builder: GetTableBucketRequest.Builder.() -> Unit = {},
): GetTableBucketRequest = GetTableBucketRequest {
    this.tableBucketArn = tableBucketArn
    builder()
}.also { it.tableBucketArn.requireNotBlank("tableBucketArn") }

fun deleteTableBucketRequestOf(
    tableBucketArn: String,
    builder: DeleteTableBucketRequest.Builder.() -> Unit = {},
): DeleteTableBucketRequest = DeleteTableBucketRequest {
    this.tableBucketArn = tableBucketArn
    builder()
}.also { it.tableBucketArn.requireNotBlank("tableBucketArn") }

fun createNamespaceRequestOf(
    tableBucketArn: String,
    namespace: List<String>,
    builder: CreateNamespaceRequest.Builder.() -> Unit = {},
): CreateNamespaceRequest = CreateNamespaceRequest {
    this.tableBucketArn = tableBucketArn
    this.namespace = namespace
    builder()
}.also {
    it.tableBucketArn.requireNotBlank("tableBucketArn")
    it.namespace.requireNamespace()
}

fun listNamespacesRequestOf(
    tableBucketArn: String,
    prefix: String? = null,
    continuationToken: String? = null,
    maxNamespaces: Int? = null,
    builder: ListNamespacesRequest.Builder.() -> Unit = {},
): ListNamespacesRequest = ListNamespacesRequest {
    this.tableBucketArn = tableBucketArn
    this.prefix = prefix
    this.continuationToken = continuationToken
    this.maxNamespaces = maxNamespaces
    builder()
}.also {
    it.tableBucketArn.requireNotBlank("tableBucketArn")
    it.validateList(it.maxNamespaces, "maxNamespaces")
}

fun getNamespaceRequestOf(
    tableBucketArn: String,
    namespace: String,
    builder: GetNamespaceRequest.Builder.() -> Unit = {},
): GetNamespaceRequest = GetNamespaceRequest {
    this.tableBucketArn = tableBucketArn
    this.namespace = namespace
    builder()
}.also {
    it.tableBucketArn.requireNotBlank("tableBucketArn")
    it.namespace.requireNotBlank("namespace")
}

fun deleteNamespaceRequestOf(
    tableBucketArn: String,
    namespace: String,
    builder: DeleteNamespaceRequest.Builder.() -> Unit = {},
): DeleteNamespaceRequest = DeleteNamespaceRequest {
    this.tableBucketArn = tableBucketArn
    this.namespace = namespace
    builder()
}.also {
    it.tableBucketArn.requireNotBlank("tableBucketArn")
    it.namespace.requireNotBlank("namespace")
}

fun createTableRequestOf(
    tableBucketArn: String,
    namespace: String,
    name: String,
    format: OpenTableFormat = OpenTableFormat.Iceberg,
    builder: CreateTableRequest.Builder.() -> Unit = {},
): CreateTableRequest = CreateTableRequest {
    this.tableBucketArn = tableBucketArn
    this.namespace = namespace
    this.name = name
    this.format = format
    builder()
}.also {
    it.tableBucketArn.requireNotBlank("tableBucketArn")
    it.namespace.requireNotBlank("namespace")
    it.name.requireNotBlank("name")
    require(it.format != null) { "format must not be null" }
}

fun listTablesRequestOf(
    tableBucketArn: String,
    namespace: String,
    prefix: String? = null,
    continuationToken: String? = null,
    maxTables: Int? = null,
    builder: ListTablesRequest.Builder.() -> Unit = {},
): ListTablesRequest = ListTablesRequest {
    this.tableBucketArn = tableBucketArn
    this.namespace = namespace
    this.prefix = prefix
    this.continuationToken = continuationToken
    this.maxTables = maxTables
    builder()
}.also {
    it.tableBucketArn.requireNotBlank("tableBucketArn")
    it.namespace.requireNotBlank("namespace")
    it.validateList(it.maxTables, "maxTables")
}

fun getTableRequestOf(
    tableBucketArn: String? = null,
    namespace: String? = null,
    name: String? = null,
    tableArn: String? = null,
    builder: GetTableRequest.Builder.() -> Unit = {},
): GetTableRequest = GetTableRequest {
    this.tableBucketArn = tableBucketArn
    this.namespace = namespace
    this.name = name
    this.tableArn = tableArn
    builder()
}.also { it.requireTableLookup() }

fun deleteTableRequestOf(
    tableBucketArn: String,
    namespace: String,
    name: String,
    versionToken: String? = null,
    builder: DeleteTableRequest.Builder.() -> Unit = {},
): DeleteTableRequest = DeleteTableRequest {
    this.tableBucketArn = tableBucketArn
    this.namespace = namespace
    this.name = name
    this.versionToken = versionToken
    builder()
}.also {
    it.tableBucketArn.requireNotBlank("tableBucketArn")
    it.namespace.requireNotBlank("namespace")
    it.name.requireNotBlank("name")
}

private fun String?.requireNotBlank(field: String) {
    require(!isNullOrBlank()) { "$field must not be blank" }
}

private fun List<String>?.requireNamespace() {
    require(!isNullOrEmpty() && all(String::isNotBlank)) {
        "namespace must contain at least one non-blank segment"
    }
}

private fun ListTableBucketsRequest.validateList(value: Int?, field: String) {
    validatePage(value, field)
    continuationToken?.requireNotBlank("continuationToken")
}

private fun ListNamespacesRequest.validateList(value: Int?, field: String) {
    validatePage(value, field)
    continuationToken?.requireNotBlank("continuationToken")
}

private fun ListTablesRequest.validateList(value: Int?, field: String) {
    validatePage(value, field)
    continuationToken?.requireNotBlank("continuationToken")
}

private fun validatePage(value: Int?, field: String) {
    require(value == null || value > 0) { "$field must be greater than zero" }
}

private fun GetTableRequest.requireTableLookup() {
    val byArn = !tableArn.isNullOrBlank()
    val byPath = !tableBucketArn.isNullOrBlank() && !namespace.isNullOrBlank() && !name.isNullOrBlank()
    require(byArn.xor(byPath)) {
        "table lookup requires either tableArn or tableBucketArn, namespace, and name"
    }
    if (byArn) {
        require(tableBucketArn.isNullOrBlank() && namespace.isNullOrBlank() && name.isNullOrBlank()) {
            "table lookup must not mix tableArn with tableBucketArn, namespace, or name"
        }
    }
}
