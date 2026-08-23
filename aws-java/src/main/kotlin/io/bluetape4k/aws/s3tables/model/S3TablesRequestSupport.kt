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

/** table bucket 생성 요청을 구성합니다. */
fun createTableBucketRequestOf(
    name: String,
    builder: CreateTableBucketRequest.Builder.() -> Unit = {},
): CreateTableBucketRequest = CreateTableBucketRequest.builder()
    .name(name)
    .apply(builder)
    .build()
    .also { it.name().requireNotBlank("name") }

/** table bucket 목록 조회 요청을 구성합니다. */
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

/** table bucket ARN으로 조회 요청을 구성합니다. */
fun getTableBucketRequestOf(
    tableBucketArn: String,
    builder: GetTableBucketRequest.Builder.() -> Unit = {},
): GetTableBucketRequest = GetTableBucketRequest.builder()
    .tableBucketARN(tableBucketArn)
    .apply(builder)
    .build()
    .also { it.tableBucketARN().requireNotBlank("tableBucketARN") }

/** table bucket ARN으로 삭제 요청을 구성합니다. */
fun deleteTableBucketRequestOf(
    tableBucketArn: String,
    builder: DeleteTableBucketRequest.Builder.() -> Unit = {},
): DeleteTableBucketRequest = DeleteTableBucketRequest.builder()
    .tableBucketARN(tableBucketArn)
    .apply(builder)
    .build()
    .also { it.tableBucketARN().requireNotBlank("tableBucketARN") }

/** table bucket에 namespace를 생성하는 요청을 구성합니다. */
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

/** namespace 목록 조회 요청을 구성합니다. */
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

/** table bucket ARN과 namespace로 조회 요청을 구성합니다. */
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

/** table bucket ARN과 namespace로 삭제 요청을 구성합니다. */
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

/** namespace에 table을 생성하는 요청을 구성합니다. */
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

/** table 목록 조회 요청을 구성합니다. [namespace]는 선택적 필터입니다. */
fun listTablesRequestOf(
    tableBucketArn: String,
    namespace: String? = null,
    prefix: String? = null,
    continuationToken: String? = null,
    maxTables: Int? = null,
    builder: ListTablesRequest.Builder.() -> Unit = {},
): ListTablesRequest = ListTablesRequest.builder()
    .tableBucketARN(tableBucketArn)
    .apply {
        namespace?.let { namespace(it) }
        prefix?.let(::prefix)
        continuationToken?.let(::continuationToken)
        maxTables?.let(::maxTables)
        builder()
    }
    .build()
    .also {
        it.tableBucketARN().requireNotBlank("tableBucketARN")
        it.namespace()?.requireNotBlank("namespace")
        it.validateList(maxBucketsField = "maxTables")
    }

/** table ARN 또는 table bucket/namespace/name selector로 조회 요청을 구성합니다. */
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

/** table bucket/namespace/name으로 삭제 요청을 구성합니다. */
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

private fun String?.requireSelector(field: String) {
    require(this == null || !isNullOrBlank()) { "$field must not be blank when provided" }
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
    val tableArn = tableArn()
    val tableBucketArn = tableBucketARN()
    val namespace = namespace()
    val name = name()
    tableArn.requireSelector("tableArn")
    tableBucketArn.requireSelector("tableBucketARN")
    namespace.requireSelector("namespace")
    name.requireSelector("name")

    val byArn = tableArn != null
    val byPath = tableBucketArn != null && namespace != null && name != null
    require(byArn.xor(byPath)) {
        "table lookup requires either tableArn or tableBucketARN, namespace, and name"
    }
    if (byArn) {
        require(tableBucketArn == null && namespace == null && name == null) {
            "table lookup must not mix tableArn with tableBucketARN, namespace, or name"
        }
    }
}
