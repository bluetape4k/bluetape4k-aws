@file:Suppress("TooManyFunctions")

package io.bluetape4k.aws.s3tables

import io.bluetape4k.aws.s3tables.model.createNamespaceRequestOf
import io.bluetape4k.aws.s3tables.model.createTableBucketRequestOf
import io.bluetape4k.aws.s3tables.model.createTableRequestOf
import io.bluetape4k.aws.s3tables.model.deleteNamespaceRequestOf
import io.bluetape4k.aws.s3tables.model.deleteTableBucketRequestOf
import io.bluetape4k.aws.s3tables.model.deleteTableRequestOf
import io.bluetape4k.aws.s3tables.model.getNamespaceRequestOf
import io.bluetape4k.aws.s3tables.model.getTableBucketRequestOf
import io.bluetape4k.aws.s3tables.model.getTableRequestOf
import io.bluetape4k.aws.s3tables.model.listNamespacesRequestOf
import io.bluetape4k.aws.s3tables.model.listTableBucketsRequestOf
import io.bluetape4k.aws.s3tables.model.listTablesRequestOf
import kotlinx.coroutines.future.await
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
import software.amazon.awssdk.services.s3tables.model.OpenTableFormat
import software.amazon.awssdk.services.s3tables.model.TableBucketType
import java.util.concurrent.CompletableFuture

/** table bucket을 생성하고 AWS SDK 원본 응답을 반환합니다. */
fun S3TablesClient.createTableBucket(
    name: String,
    builder: CreateTableBucketRequest.Builder.() -> Unit = {},
): CreateTableBucketResponse = createTableBucket(createTableBucketRequestOf(name, builder))

/** table bucket 목록을 한 페이지 조회합니다. */
fun S3TablesClient.listTableBuckets(
    prefix: String? = null,
    continuationToken: String? = null,
    maxBuckets: Int? = null,
    type: TableBucketType? = null,
    builder: ListTableBucketsRequest.Builder.() -> Unit = {},
): ListTableBucketsResponse = listTableBuckets(
    listTableBucketsRequestOf(prefix, continuationToken, maxBuckets, type, builder),
)

/** table bucket ARN으로 table bucket을 조회합니다. */
fun S3TablesClient.getTableBucket(
    tableBucketArn: String,
    builder: GetTableBucketRequest.Builder.() -> Unit = {},
): GetTableBucketResponse = getTableBucket(getTableBucketRequestOf(tableBucketArn, builder))

/** table bucket ARN으로 table bucket을 삭제합니다. */
fun S3TablesClient.deleteTableBucket(
    tableBucketArn: String,
    builder: DeleteTableBucketRequest.Builder.() -> Unit = {},
): DeleteTableBucketResponse = deleteTableBucket(deleteTableBucketRequestOf(tableBucketArn, builder))

/** table bucket에 namespace를 생성합니다. */
fun S3TablesClient.createNamespace(
    tableBucketArn: String,
    namespace: List<String>,
    builder: CreateNamespaceRequest.Builder.() -> Unit = {},
): CreateNamespaceResponse = createNamespace(createNamespaceRequestOf(tableBucketArn, namespace, builder))

/** namespace 목록을 한 페이지 조회합니다. */
fun S3TablesClient.listNamespaces(
    tableBucketArn: String,
    prefix: String? = null,
    continuationToken: String? = null,
    maxNamespaces: Int? = null,
    builder: ListNamespacesRequest.Builder.() -> Unit = {},
): ListNamespacesResponse = listNamespaces(
    listNamespacesRequestOf(tableBucketArn, prefix, continuationToken, maxNamespaces, builder),
)

/** table bucket ARN과 namespace로 namespace를 조회합니다. */
fun S3TablesClient.getNamespace(
    tableBucketArn: String,
    namespace: String,
    builder: GetNamespaceRequest.Builder.() -> Unit = {},
): GetNamespaceResponse = getNamespace(getNamespaceRequestOf(tableBucketArn, namespace, builder))

/** table bucket ARN과 namespace로 namespace를 삭제합니다. */
fun S3TablesClient.deleteNamespace(
    tableBucketArn: String,
    namespace: String,
    builder: DeleteNamespaceRequest.Builder.() -> Unit = {},
): DeleteNamespaceResponse = deleteNamespace(deleteNamespaceRequestOf(tableBucketArn, namespace, builder))

/** namespace에 Iceberg table을 생성합니다. */
fun S3TablesClient.createTable(
    tableBucketArn: String,
    namespace: String,
    name: String,
    format: OpenTableFormat = OpenTableFormat.ICEBERG,
    builder: CreateTableRequest.Builder.() -> Unit = {},
): CreateTableResponse = createTable(createTableRequestOf(tableBucketArn, namespace, name, format, builder))

/** table 목록을 한 페이지 조회합니다. [namespace]는 선택적 필터입니다. */
fun S3TablesClient.listTables(
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
fun S3TablesClient.getTable(
    tableBucketArn: String? = null,
    namespace: String? = null,
    name: String? = null,
    tableArn: String? = null,
    builder: GetTableRequest.Builder.() -> Unit = {},
): GetTableResponse = getTable(getTableRequestOf(tableBucketArn, namespace, name, tableArn, builder))

/** table bucket/namespace/name으로 table을 삭제합니다. */
fun S3TablesClient.deleteTable(
    tableBucketArn: String,
    namespace: String,
    name: String,
    versionToken: String? = null,
    builder: DeleteTableRequest.Builder.() -> Unit = {},
): DeleteTableResponse = deleteTable(
    deleteTableRequestOf(tableBucketArn, namespace, name, versionToken, builder),
)

/** 비동기 방식으로 table bucket을 생성합니다. */
fun S3TablesAsyncClient.createTableBucketAsync(
    name: String,
    builder: CreateTableBucketRequest.Builder.() -> Unit = {},
): CompletableFuture<CreateTableBucketResponse> =
    createTableBucket(createTableBucketRequestOf(name, builder))

/** 비동기 방식으로 table bucket 목록을 한 페이지 조회합니다. */
fun S3TablesAsyncClient.listTableBucketsAsync(
    prefix: String? = null,
    continuationToken: String? = null,
    maxBuckets: Int? = null,
    type: TableBucketType? = null,
    builder: ListTableBucketsRequest.Builder.() -> Unit = {},
): CompletableFuture<ListTableBucketsResponse> = listTableBuckets(
    listTableBucketsRequestOf(prefix, continuationToken, maxBuckets, type, builder),
)

/** 비동기 방식으로 table bucket을 조회합니다. */
fun S3TablesAsyncClient.getTableBucketAsync(
    tableBucketArn: String,
    builder: GetTableBucketRequest.Builder.() -> Unit = {},
): CompletableFuture<GetTableBucketResponse> =
    getTableBucket(getTableBucketRequestOf(tableBucketArn, builder))

/** 비동기 방식으로 table bucket을 삭제합니다. */
fun S3TablesAsyncClient.deleteTableBucketAsync(
    tableBucketArn: String,
    builder: DeleteTableBucketRequest.Builder.() -> Unit = {},
): CompletableFuture<DeleteTableBucketResponse> =
    deleteTableBucket(deleteTableBucketRequestOf(tableBucketArn, builder))

/** 비동기 방식으로 namespace를 생성합니다. */
fun S3TablesAsyncClient.createNamespaceAsync(
    tableBucketArn: String,
    namespace: List<String>,
    builder: CreateNamespaceRequest.Builder.() -> Unit = {},
): CompletableFuture<CreateNamespaceResponse> =
    createNamespace(createNamespaceRequestOf(tableBucketArn, namespace, builder))

/** 비동기 방식으로 namespace 목록을 한 페이지 조회합니다. */
fun S3TablesAsyncClient.listNamespacesAsync(
    tableBucketArn: String,
    prefix: String? = null,
    continuationToken: String? = null,
    maxNamespaces: Int? = null,
    builder: ListNamespacesRequest.Builder.() -> Unit = {},
): CompletableFuture<ListNamespacesResponse> = listNamespaces(
    listNamespacesRequestOf(tableBucketArn, prefix, continuationToken, maxNamespaces, builder),
)

/** 비동기 방식으로 namespace를 조회합니다. */
fun S3TablesAsyncClient.getNamespaceAsync(
    tableBucketArn: String,
    namespace: String,
    builder: GetNamespaceRequest.Builder.() -> Unit = {},
): CompletableFuture<GetNamespaceResponse> =
    getNamespace(getNamespaceRequestOf(tableBucketArn, namespace, builder))

/** 비동기 방식으로 namespace를 삭제합니다. */
fun S3TablesAsyncClient.deleteNamespaceAsync(
    tableBucketArn: String,
    namespace: String,
    builder: DeleteNamespaceRequest.Builder.() -> Unit = {},
): CompletableFuture<DeleteNamespaceResponse> =
    deleteNamespace(deleteNamespaceRequestOf(tableBucketArn, namespace, builder))

/** 비동기 방식으로 Iceberg table을 생성합니다. */
fun S3TablesAsyncClient.createTableAsync(
    tableBucketArn: String,
    namespace: String,
    name: String,
    format: OpenTableFormat = OpenTableFormat.ICEBERG,
    builder: CreateTableRequest.Builder.() -> Unit = {},
): CompletableFuture<CreateTableResponse> =
    createTable(createTableRequestOf(tableBucketArn, namespace, name, format, builder))

/** 비동기 방식으로 table 목록을 한 페이지 조회합니다. [namespace]는 선택적 필터입니다. */
fun S3TablesAsyncClient.listTablesAsync(
    tableBucketArn: String,
    namespace: String? = null,
    prefix: String? = null,
    continuationToken: String? = null,
    maxTables: Int? = null,
    builder: ListTablesRequest.Builder.() -> Unit = {},
): CompletableFuture<ListTablesResponse> = listTables(
    listTablesRequestOf(tableBucketArn, namespace, prefix, continuationToken, maxTables, builder),
)

/** 비동기 방식으로 table ARN 또는 table path selector로 table을 조회합니다. */
fun S3TablesAsyncClient.getTableAsync(
    tableBucketArn: String? = null,
    namespace: String? = null,
    name: String? = null,
    tableArn: String? = null,
    builder: GetTableRequest.Builder.() -> Unit = {},
): CompletableFuture<GetTableResponse> =
    getTable(getTableRequestOf(tableBucketArn, namespace, name, tableArn, builder))

/** 비동기 방식으로 table을 삭제합니다. */
fun S3TablesAsyncClient.deleteTableAsync(
    tableBucketArn: String,
    namespace: String,
    name: String,
    versionToken: String? = null,
    builder: DeleteTableRequest.Builder.() -> Unit = {},
): CompletableFuture<DeleteTableResponse> = deleteTable(
    deleteTableRequestOf(tableBucketArn, namespace, name, versionToken, builder),
)

/** [S3TablesAsyncClient]로 table bucket을 생성하는 suspend helper입니다. */
suspend fun S3TablesAsyncClient.createTableBucket(
    name: String,
    builder: CreateTableBucketRequest.Builder.() -> Unit = {},
): CreateTableBucketResponse = createTableBucketAsync(name, builder).await()

/** [S3TablesAsyncClient]로 table bucket 목록을 한 페이지 조회하는 suspend helper입니다. */
suspend fun S3TablesAsyncClient.listTableBuckets(
    prefix: String? = null,
    continuationToken: String? = null,
    maxBuckets: Int? = null,
    type: TableBucketType? = null,
    builder: ListTableBucketsRequest.Builder.() -> Unit = {},
): ListTableBucketsResponse = listTableBucketsAsync(prefix, continuationToken, maxBuckets, type, builder).await()

/** [S3TablesAsyncClient]로 table bucket을 조회하는 suspend helper입니다. */
suspend fun S3TablesAsyncClient.getTableBucket(
    tableBucketArn: String,
    builder: GetTableBucketRequest.Builder.() -> Unit = {},
): GetTableBucketResponse = getTableBucketAsync(tableBucketArn, builder).await()

/** [S3TablesAsyncClient]로 table bucket을 삭제하는 suspend helper입니다. */
suspend fun S3TablesAsyncClient.deleteTableBucket(
    tableBucketArn: String,
    builder: DeleteTableBucketRequest.Builder.() -> Unit = {},
): DeleteTableBucketResponse = deleteTableBucketAsync(tableBucketArn, builder).await()

/** [S3TablesAsyncClient]로 namespace를 생성하는 suspend helper입니다. */
suspend fun S3TablesAsyncClient.createNamespace(
    tableBucketArn: String,
    namespace: List<String>,
    builder: CreateNamespaceRequest.Builder.() -> Unit = {},
): CreateNamespaceResponse = createNamespaceAsync(tableBucketArn, namespace, builder).await()

/** [S3TablesAsyncClient]로 namespace 목록을 한 페이지 조회하는 suspend helper입니다. */
suspend fun S3TablesAsyncClient.listNamespaces(
    tableBucketArn: String,
    prefix: String? = null,
    continuationToken: String? = null,
    maxNamespaces: Int? = null,
    builder: ListNamespacesRequest.Builder.() -> Unit = {},
): ListNamespacesResponse =
    listNamespacesAsync(tableBucketArn, prefix, continuationToken, maxNamespaces, builder).await()

/** [S3TablesAsyncClient]로 namespace를 조회하는 suspend helper입니다. */
suspend fun S3TablesAsyncClient.getNamespace(
    tableBucketArn: String,
    namespace: String,
    builder: GetNamespaceRequest.Builder.() -> Unit = {},
): GetNamespaceResponse = getNamespaceAsync(tableBucketArn, namespace, builder).await()

/** [S3TablesAsyncClient]로 namespace를 삭제하는 suspend helper입니다. */
suspend fun S3TablesAsyncClient.deleteNamespace(
    tableBucketArn: String,
    namespace: String,
    builder: DeleteNamespaceRequest.Builder.() -> Unit = {},
): DeleteNamespaceResponse = deleteNamespaceAsync(tableBucketArn, namespace, builder).await()

/** [S3TablesAsyncClient]로 Iceberg table을 생성하는 suspend helper입니다. */
suspend fun S3TablesAsyncClient.createTable(
    tableBucketArn: String,
    namespace: String,
    name: String,
    format: OpenTableFormat = OpenTableFormat.ICEBERG,
    builder: CreateTableRequest.Builder.() -> Unit = {},
): CreateTableResponse = createTableAsync(tableBucketArn, namespace, name, format, builder).await()

/** [S3TablesAsyncClient]로 table 목록을 한 페이지 조회하는 suspend helper입니다. [namespace]는 선택적 필터입니다. */
suspend fun S3TablesAsyncClient.listTables(
    tableBucketArn: String,
    namespace: String? = null,
    prefix: String? = null,
    continuationToken: String? = null,
    maxTables: Int? = null,
    builder: ListTablesRequest.Builder.() -> Unit = {},
): ListTablesResponse =
    listTablesAsync(tableBucketArn, namespace, prefix, continuationToken, maxTables, builder).await()

/** [S3TablesAsyncClient]로 table ARN 또는 table path selector로 table을 조회하는 suspend helper입니다. */
suspend fun S3TablesAsyncClient.getTable(
    tableBucketArn: String? = null,
    namespace: String? = null,
    name: String? = null,
    tableArn: String? = null,
    builder: GetTableRequest.Builder.() -> Unit = {},
): GetTableResponse = getTableAsync(tableBucketArn, namespace, name, tableArn, builder).await()

/** [S3TablesAsyncClient]로 table을 삭제하는 suspend helper입니다. */
suspend fun S3TablesAsyncClient.deleteTable(
    tableBucketArn: String,
    namespace: String,
    name: String,
    versionToken: String? = null,
    builder: DeleteTableRequest.Builder.() -> Unit = {},
): DeleteTableResponse = deleteTableAsync(tableBucketArn, namespace, name, versionToken, builder).await()
