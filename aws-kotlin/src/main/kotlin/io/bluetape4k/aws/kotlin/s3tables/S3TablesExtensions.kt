@file:Suppress("TooManyFunctions")

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
import aws.sdk.kotlin.services.s3tables.model.OpenTableFormat
import aws.sdk.kotlin.services.s3tables.model.TableBucketType
import io.bluetape4k.aws.kotlin.s3tables.model.createNamespaceRequestOf
import io.bluetape4k.aws.kotlin.s3tables.model.createTableBucketRequestOf
import io.bluetape4k.aws.kotlin.s3tables.model.createTableRequestOf
import io.bluetape4k.aws.kotlin.s3tables.model.deleteNamespaceRequestOf
import io.bluetape4k.aws.kotlin.s3tables.model.deleteTableBucketRequestOf
import io.bluetape4k.aws.kotlin.s3tables.model.deleteTableRequestOf
import io.bluetape4k.aws.kotlin.s3tables.model.getNamespaceRequestOf
import io.bluetape4k.aws.kotlin.s3tables.model.getTableBucketRequestOf
import io.bluetape4k.aws.kotlin.s3tables.model.getTableRequestOf
import io.bluetape4k.aws.kotlin.s3tables.model.listNamespacesRequestOf
import io.bluetape4k.aws.kotlin.s3tables.model.listTableBucketsRequestOf
import io.bluetape4k.aws.kotlin.s3tables.model.listTablesRequestOf

/** table bucket을 생성하고 AWS Kotlin SDK 원본 응답을 반환합니다. */
suspend fun S3TablesClient.createTableBucket(
    name: String,
    builder: CreateTableBucketRequest.Builder.() -> Unit = {},
): CreateTableBucketResponse = createTableBucket(createTableBucketRequestOf(name, builder))

/** table bucket 목록을 한 페이지 조회합니다. */
suspend fun S3TablesClient.listTableBuckets(
    prefix: String? = null,
    continuationToken: String? = null,
    maxBuckets: Int? = null,
    type: TableBucketType? = null,
    builder: ListTableBucketsRequest.Builder.() -> Unit = {},
): ListTableBucketsResponse = listTableBuckets(
    listTableBucketsRequestOf(prefix, continuationToken, maxBuckets, type, builder),
)

/** table bucket ARN으로 table bucket을 조회합니다. */
suspend fun S3TablesClient.getTableBucket(
    tableBucketArn: String,
    builder: GetTableBucketRequest.Builder.() -> Unit = {},
): GetTableBucketResponse = getTableBucket(getTableBucketRequestOf(tableBucketArn, builder))

/** table bucket ARN으로 table bucket을 삭제합니다. */
suspend fun S3TablesClient.deleteTableBucket(
    tableBucketArn: String,
    builder: DeleteTableBucketRequest.Builder.() -> Unit = {},
): DeleteTableBucketResponse = deleteTableBucket(deleteTableBucketRequestOf(tableBucketArn, builder))

/** table bucket에 namespace를 생성합니다. */
suspend fun S3TablesClient.createNamespace(
    tableBucketArn: String,
    namespace: List<String>,
    builder: CreateNamespaceRequest.Builder.() -> Unit = {},
): CreateNamespaceResponse = createNamespace(createNamespaceRequestOf(tableBucketArn, namespace, builder))

/** namespace 목록을 한 페이지 조회합니다. */
suspend fun S3TablesClient.listNamespaces(
    tableBucketArn: String,
    prefix: String? = null,
    continuationToken: String? = null,
    maxNamespaces: Int? = null,
    builder: ListNamespacesRequest.Builder.() -> Unit = {},
): ListNamespacesResponse = listNamespaces(
    listNamespacesRequestOf(tableBucketArn, prefix, continuationToken, maxNamespaces, builder),
)

/** table bucket ARN과 namespace로 namespace를 조회합니다. */
suspend fun S3TablesClient.getNamespace(
    tableBucketArn: String,
    namespace: String,
    builder: GetNamespaceRequest.Builder.() -> Unit = {},
): GetNamespaceResponse = getNamespace(getNamespaceRequestOf(tableBucketArn, namespace, builder))

/** table bucket ARN과 namespace로 namespace를 삭제합니다. */
suspend fun S3TablesClient.deleteNamespace(
    tableBucketArn: String,
    namespace: String,
    builder: DeleteNamespaceRequest.Builder.() -> Unit = {},
): DeleteNamespaceResponse = deleteNamespace(deleteNamespaceRequestOf(tableBucketArn, namespace, builder))

/** namespace에 Iceberg table을 생성합니다. */
suspend fun S3TablesClient.createTable(
    tableBucketArn: String,
    namespace: String,
    name: String,
    format: OpenTableFormat = OpenTableFormat.Iceberg,
    builder: CreateTableRequest.Builder.() -> Unit = {},
): CreateTableResponse = createTable(createTableRequestOf(tableBucketArn, namespace, name, format, builder))

/** table 목록을 한 페이지 조회합니다. [namespace]는 선택적 필터입니다. */
suspend fun S3TablesClient.listTables(
    tableBucketArn: String,
    namespace: String? = null,
    prefix: String? = null,
    continuationToken: String? = null,
    maxTables: Int? = null,
    builder: ListTablesRequest.Builder.() -> Unit = {},
): ListTablesResponse = listTables(
    listTablesRequestOf(tableBucketArn, namespace, prefix, continuationToken, maxTables, builder),
)

/** table ARN 또는 table bucket/namespace/name selector로 table을 조회합니다. */
suspend fun S3TablesClient.getTable(
    tableBucketArn: String? = null,
    namespace: String? = null,
    name: String? = null,
    tableArn: String? = null,
    builder: GetTableRequest.Builder.() -> Unit = {},
): GetTableResponse = getTable(getTableRequestOf(tableBucketArn, namespace, name, tableArn, builder))

/** table bucket/namespace/name으로 table을 삭제합니다. */
suspend fun S3TablesClient.deleteTable(
    tableBucketArn: String,
    namespace: String,
    name: String,
    versionToken: String? = null,
    builder: DeleteTableRequest.Builder.() -> Unit = {},
): DeleteTableResponse = deleteTable(deleteTableRequestOf(tableBucketArn, namespace, name, versionToken, builder))
