@file:Suppress("TooManyFunctions")

package io.bluetape4k.aws.s3tables.model

import software.amazon.awssdk.services.s3tables.model.CreateNamespaceRequest
import software.amazon.awssdk.services.s3tables.model.CreateTableBucketRequest
import software.amazon.awssdk.services.s3tables.model.CreateTableRequest
import software.amazon.awssdk.services.s3tables.model.DeleteNamespaceRequest
import software.amazon.awssdk.services.s3tables.model.DeleteTableBucketRequest
import software.amazon.awssdk.services.s3tables.model.DeleteTableRequest
import software.amazon.awssdk.services.s3tables.model.GetNamespaceRequest
import software.amazon.awssdk.services.s3tables.model.GetTableBucketRequest
import software.amazon.awssdk.services.s3tables.model.GetTableRequest
import software.amazon.awssdk.services.s3tables.model.ListNamespacesRequest
import software.amazon.awssdk.services.s3tables.model.ListTableBucketsRequest
import software.amazon.awssdk.services.s3tables.model.ListTablesRequest
import software.amazon.awssdk.services.s3tables.model.OpenTableFormat
import software.amazon.awssdk.services.s3tables.model.TableBucketType

fun createTableBucketRequestOf(
    name: String,
    builder: CreateTableBucketRequest.Builder.() -> Unit = {},
): CreateTableBucketRequest = CreateTableBucketRequest.builder()
    .name(name)
    .apply(builder)
    .build()
    .also { it.name().requireNotBlank("name") }

fun listTableBucketsRequestOf(
    prefix: String? = null,
    continuationToken: String? = null,
    maxBuckets: Int? = null,
    type: TableBucketType? = null,
    builder: ListTableBucketsRequest.Builder.() -> Unit = {},
): ListTableBucketsRequest = ListTableBucketsRequest.builder()
    .apply {
        prefix?.let(::prefix)
        continuationToken?.let(::continuationToken)
        maxBuckets?.let(::maxBuckets)
        type?.let(::type)
        builder()
    }
    .build()
    .also { it.validateList(maxBucketsField = "maxBuckets") }

fun getTableBucketRequestOf(
    tableBucketArn: String,
    builder: GetTableBucketRequest.Builder.() -> Unit = {},
): GetTableBucketRequest = GetTableBucketRequest.builder()
    .tableBucketARN(tableBucketArn)
    .apply(builder)
    .build()
    .also { it.tableBucketARN().requireNotBlank("tableBucketARN") }

fun deleteTableBucketRequestOf(
    tableBucketArn: String,
    builder: DeleteTableBucketRequest.Builder.() -> Unit = {},
): DeleteTableBucketRequest = DeleteTableBucketRequest.builder()
    .tableBucketARN(tableBucketArn)
    .apply(builder)
    .build()
    .also { it.tableBucketARN().requireNotBlank("tableBucketARN") }

fun createNamespaceRequestOf(
    tableBucketArn: String,
    namespace: List<String>,
    builder: CreateNamespaceRequest.Builder.() -> Unit = {},
): CreateNamespaceRequest = CreateNamespaceRequest.builder()
    .tableBucketARN(tableBucketArn)
    .namespace(namespace)
    .apply(builder)
    .build()
    .also {
        it.tableBucketARN().requireNotBlank("tableBucketARN")
        it.namespace().requireNamespace()
    }

fun listNamespacesRequestOf(
    tableBucketArn: String,
    prefix: String? = null,
    continuationToken: String? = null,
    maxNamespaces: Int? = null,
    builder: ListNamespacesRequest.Builder.() -> Unit = {},
): ListNamespacesRequest = ListNamespacesRequest.builder()
    .tableBucketARN(tableBucketArn)
    .apply {
        prefix?.let(::prefix)
        continuationToken?.let(::continuationToken)
        maxNamespaces?.let(::maxNamespaces)
        builder()
    }
    .build()
    .also {
        it.tableBucketARN().requireNotBlank("tableBucketARN")
        it.validateList(maxBucketsField = "maxNamespaces")
    }

fun getNamespaceRequestOf(
    tableBucketArn: String,
    namespace: String,
    builder: GetNamespaceRequest.Builder.() -> Unit = {},
): GetNamespaceRequest = GetNamespaceRequest.builder()
    .tableBucketARN(tableBucketArn)
    .namespace(namespace)
    .apply(builder)
    .build()
    .also {
        it.tableBucketARN().requireNotBlank("tableBucketARN")
        it.namespace().requireNotBlank("namespace")
    }

fun deleteNamespaceRequestOf(
    tableBucketArn: String,
    namespace: String,
    builder: DeleteNamespaceRequest.Builder.() -> Unit = {},
): DeleteNamespaceRequest = DeleteNamespaceRequest.builder()
    .tableBucketARN(tableBucketArn)
    .namespace(namespace)
    .apply(builder)
    .build()
    .also {
        it.tableBucketARN().requireNotBlank("tableBucketARN")
        it.namespace().requireNotBlank("namespace")
    }

fun createTableRequestOf(
    tableBucketArn: String,
    namespace: String,
    name: String,
    format: OpenTableFormat = OpenTableFormat.ICEBERG,
    builder: CreateTableRequest.Builder.() -> Unit = {},
): CreateTableRequest = CreateTableRequest.builder()
    .tableBucketARN(tableBucketArn)
    .namespace(namespace)
    .name(name)
    .format(format)
    .apply(builder)
    .build()
    .also {
        it.tableBucketARN().requireNotBlank("tableBucketARN")
        it.namespace().requireNotBlank("namespace")
        it.name().requireNotBlank("name")
        require(it.format() != null) { "format must not be null" }
    }

fun listTablesRequestOf(
    tableBucketArn: String,
    namespace: String,
    prefix: String? = null,
    continuationToken: String? = null,
    maxTables: Int? = null,
    builder: ListTablesRequest.Builder.() -> Unit = {},
): ListTablesRequest = ListTablesRequest.builder()
    .tableBucketARN(tableBucketArn)
    .namespace(namespace)
    .apply {
        prefix?.let(::prefix)
        continuationToken?.let(::continuationToken)
        maxTables?.let(::maxTables)
        builder()
    }
    .build()
    .also {
        it.tableBucketARN().requireNotBlank("tableBucketARN")
        it.namespace().requireNotBlank("namespace")
        it.validateList(maxBucketsField = "maxTables")
    }

fun getTableRequestOf(
    tableBucketArn: String? = null,
    namespace: String? = null,
    name: String? = null,
    tableArn: String? = null,
    builder: GetTableRequest.Builder.() -> Unit = {},
): GetTableRequest = GetTableRequest.builder()
    .apply {
        tableBucketArn?.let(::tableBucketARN)
        namespace?.let(::namespace)
        name?.let(::name)
        tableArn?.let(::tableArn)
        builder()
    }
    .build()
    .also { it.requireTableLookup() }

fun deleteTableRequestOf(
    tableBucketArn: String,
    namespace: String,
    name: String,
    versionToken: String? = null,
    builder: DeleteTableRequest.Builder.() -> Unit = {},
): DeleteTableRequest = DeleteTableRequest.builder()
    .tableBucketARN(tableBucketArn)
    .namespace(namespace)
    .name(name)
    .apply {
        versionToken?.let(::versionToken)
        builder()
    }
    .build()
    .also {
        it.tableBucketARN().requireNotBlank("tableBucketARN")
        it.namespace().requireNotBlank("namespace")
        it.name().requireNotBlank("name")
    }

private fun String?.requireNotBlank(field: String) {
    require(!isNullOrBlank()) { "$field must not be blank" }
}

private fun List<String>?.requireNamespace() {
    require(!isNullOrEmpty() && all(String::isNotBlank)) {
        "namespace must contain at least one non-blank segment"
    }
}

private fun ListTableBucketsRequest.validateList(maxBucketsField: String) {
    validatePage(maxBucketsField, maxBuckets())
    continuationToken()?.requireNotBlank("continuationToken")
}

private fun ListNamespacesRequest.validateList(maxBucketsField: String) {
    validatePage(maxBucketsField, maxNamespaces())
    continuationToken()?.requireNotBlank("continuationToken")
}

private fun ListTablesRequest.validateList(maxBucketsField: String) {
    validatePage(maxBucketsField, maxTables())
    continuationToken()?.requireNotBlank("continuationToken")
}

private fun validatePage(field: String, value: Int?) {
    require(value == null || value > 0) { "$field must be greater than zero" }
}

private fun GetTableRequest.requireTableLookup() {
    val byArn = !tableArn().isNullOrBlank()
    val byPath = !tableBucketARN().isNullOrBlank() && !namespace().isNullOrBlank() && !name().isNullOrBlank()
    require(byArn.xor(byPath)) {
        "table lookup requires either tableArn or tableBucketARN, namespace, and name"
    }
    if (byArn) {
        require(tableBucketARN().isNullOrBlank() && namespace().isNullOrBlank() && name().isNullOrBlank()) {
            "table lookup must not mix tableArn with tableBucketARN, namespace, or name"
        }
    }
}
