package io.bluetape4k.aws.ktor.dynamodb

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.deleteItem
import aws.sdk.kotlin.services.dynamodb.getItem
import aws.sdk.kotlin.services.dynamodb.model.DeleteItemRequest
import aws.sdk.kotlin.services.dynamodb.model.DeleteItemResponse
import aws.sdk.kotlin.services.dynamodb.model.GetItemRequest
import aws.sdk.kotlin.services.dynamodb.model.PutItemRequest
import aws.sdk.kotlin.services.dynamodb.model.PutItemResponse
import aws.sdk.kotlin.services.dynamodb.model.QueryRequest
import aws.sdk.kotlin.services.dynamodb.model.ScanRequest
import aws.sdk.kotlin.services.dynamodb.paginators.queryPaginated
import aws.sdk.kotlin.services.dynamodb.paginators.scanPaginated
import aws.sdk.kotlin.services.dynamodb.putItem
import io.bluetape4k.aws.kotlin.dynamodb.DynamoItemMapper
import io.bluetape4k.aws.kotlin.dynamodb.DynamoItemReader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/**
 * Coroutine repository facade for one DynamoDB table.
 *
 * Contract:
 * - Uses explicit [mapper], [reader], and [keyMapper] functions; no reflection
 *   and no preview AWS Kotlin DynamoDB mapper.
 * - Exposes only stable v1 operations: save, find, delete, scan, and query.
 * - Advanced update expressions, batch reads, and schema verification stay in
 *   lower-level AWS Kotlin SDK APIs for now.
 */
class DynamoDbKtorRepository<T: Any, K: Any>(
    private val dynamoDbClient: DynamoDbClient,
    private val tableName: String,
    private val mapper: DynamoItemMapper<T>,
    private val reader: DynamoItemReader<T>,
    private val keyMapper: DynamoItemMapper<K>,
) {
    init {
        require(tableName.isNotBlank()) { "tableName must not be blank." }
    }

    /**
     * Saves [item] with PutItem and returns the original input item.
     *
     * PutItem does not echo the stored item by default; use [put] when the AWS
     * response metadata is needed.
     */
    suspend fun save(
        item: T,
        builder: PutItemRequest.Builder.() -> Unit = {},
    ): T {
        put(item, builder)
        return item
    }

    /**
     * Saves [item] with PutItem and returns the AWS response.
     */
    suspend fun put(
        item: T,
        builder: PutItemRequest.Builder.() -> Unit = {},
    ): PutItemResponse =
        dynamoDbClient.putItem {
            this.tableName = this@DynamoDbKtorRepository.tableName
            this.item = mapper.mapToDynamoItem(item)
            builder()
        }

    /**
     * Finds one item by the mapped DynamoDB key.
     */
    suspend fun findById(
        id: K,
        builder: GetItemRequest.Builder.() -> Unit = {},
    ): T? {
        val response = dynamoDbClient.getItem {
            this.tableName = this@DynamoDbKtorRepository.tableName
            this.key = keyMapper.mapToDynamoItem(id)
            builder()
        }
        return response.item?.let(reader::readDynamoItem)
    }

    /**
     * Deletes one item by the mapped DynamoDB key.
     */
    suspend fun deleteById(
        id: K,
        builder: DeleteItemRequest.Builder.() -> Unit = {},
    ): DeleteItemResponse =
        dynamoDbClient.deleteItem {
            this.tableName = this@DynamoDbKtorRepository.tableName
            this.key = keyMapper.mapToDynamoItem(id)
            builder()
        }

    /**
     * Scans this table and streams mapped items.
     */
    fun scan(
        builder: ScanRequest.Builder.() -> Unit = {},
    ): Flow<T> =
        flow {
            dynamoDbClient.scanPaginated {
                this.tableName = this@DynamoDbKtorRepository.tableName
                builder()
            }.collect { page ->
                page.items.orEmpty().forEach { emit(reader.readDynamoItem(it)) }
            }
        }

    /**
     * Queries this table and streams mapped items.
     */
    fun query(
        builder: QueryRequest.Builder.() -> Unit,
    ): Flow<T> =
        flow {
            dynamoDbClient.queryPaginated {
                this.tableName = this@DynamoDbKtorRepository.tableName
                builder()
            }.collect { page ->
                page.items.orEmpty().forEach { emit(reader.readDynamoItem(it)) }
            }
        }
}
