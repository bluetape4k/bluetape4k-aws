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

/** table bucket 생성 요청을 구성합니다. */
fun createTableBucketRequestOf(
    name: String,
    builder: CreateTableBucketRequest.Builder.() -> Unit = {},
): CreateTableBucketRequest = CreateTableBucketRequest {
    this.name = name
    builder()
}.also { it.name.requireNotBlank("name") }

/** table bucket 목록 조회 요청을 구성합니다. */
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

/** table bucket ARN으로 조회 요청을 구성합니다. */
fun getTableBucketRequestOf(
    tableBucketArn: String,
    builder: GetTableBucketRequest.Builder.() -> Unit = {},
): GetTableBucketRequest = GetTableBucketRequest {
    this.tableBucketArn = tableBucketArn
    builder()
}.also { it.tableBucketArn.requireNotBlank("tableBucketArn") }

/** table bucket ARN으로 삭제 요청을 구성합니다. */
fun deleteTableBucketRequestOf(
    tableBucketArn: String,
    builder: DeleteTableBucketRequest.Builder.() -> Unit = {},
): DeleteTableBucketRequest = DeleteTableBucketRequest {
    this.tableBucketArn = tableBucketArn
    builder()
}.also { it.tableBucketArn.requireNotBlank("tableBucketArn") }

/** table bucket에 namespace를 생성하는 요청을 구성합니다. */
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

/** namespace 목록 조회 요청을 구성합니다. */
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

/** table bucket ARN과 namespace로 조회 요청을 구성합니다. */
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

/** table bucket ARN과 namespace로 삭제 요청을 구성합니다. */
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

/** namespace에 table을 생성하는 요청을 구성합니다. */
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

/** table 목록 조회 요청을 구성합니다. [namespace]는 선택적 필터입니다. */
fun listTablesRequestOf(
    tableBucketArn: String,
    namespace: String? = null,
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
    it.namespace?.requireNotBlank("namespace")
    it.validateList(it.maxTables, "maxTables")
}

/** table ARN 또는 table bucket/namespace/name selector로 조회 요청을 구성합니다. */
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

/** table bucket/namespace/name으로 삭제 요청을 구성합니다. */
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

private fun String?.requireSelector(field: String) {
    require(this == null || !isNullOrBlank()) { "$field must not be blank when provided" }
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
    tableArn.requireSelector("tableArn")
    tableBucketArn.requireSelector("tableBucketArn")
    namespace.requireSelector("namespace")
    name.requireSelector("name")

    val byArn = tableArn != null
    val byPath = tableBucketArn != null && namespace != null && name != null
    require(byArn.xor(byPath)) {
        "table lookup requires either tableArn or tableBucketArn, namespace, and name"
    }
    if (byArn) {
        require(tableBucketArn == null && namespace == null && name == null) {
            "table lookup must not mix tableArn with tableBucketArn, namespace, or name"
        }
    }
}
