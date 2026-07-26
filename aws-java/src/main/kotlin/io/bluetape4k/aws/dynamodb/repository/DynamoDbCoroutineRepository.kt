package io.bluetape4k.aws.dynamodb.repository

import io.bluetape4k.aws.dynamodb.enhanced.batchWriteItems
import io.bluetape4k.aws.dynamodb.model.DynamoDbEntity
import io.bluetape4k.aws.dynamodb.model.keyOf
import io.bluetape4k.coroutines.flow.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.future.await
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient
import software.amazon.awssdk.enhanced.dynamodb.Key
import software.amazon.awssdk.enhanced.dynamodb.model.BatchWriteResult
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest

/**
 * See the API documentation for details.
 *
 * See the API documentation for details.
 *
 * ```kotlin
 * val saved = repository.update(entity)
 * check(saved?.key == entity.key)
 * ```
 */
interface DynamoDbCoroutineRepository<T: DynamoDbEntity> {

    /** Repository가 사용하는 Enhanced Async Client 입니다. */
    val client: DynamoDbEnhancedAsyncClient

    /** CRUD 대상 테이블입니다. */
    val table: DynamoDbAsyncTable<T>

    /** 배치 쓰기 시 사용하는 엔티티 클래스입니다. */
    val itemClass: Class<T>

    /**
     * See the API documentation for details.
     *
     * ```kotlin
     * val found = repository.findByKey(key)
     * check(found == null || found.key == key)
     * ```
     */
    suspend fun findByKey(key: Key): T? {
        return table.getItem(key).await()
    }

    /**
     * See the API documentation for details.
     *
     * See the API documentation for details.
     */
    suspend fun findFirst(request: QueryEnhancedRequest): List<T> {
        return table.query(request).findFirst()
    }

    /**
     * See the API documentation for details.
     *
     * ```kotlin
     * val items = repository.findFirstByPartitionKey("customer#1")
     * check(items is List<*>)
     * ```
     */
    suspend fun findFirstByPartitionKey(partitionKey: String): List<T> {
        val request = QueryEnhancedRequest.builder()
            .queryConditional(QueryConditional.keyEqualTo(keyOf(partitionKey)))
            .build()
        return findFirst(request)
    }

    /**
     * See the API documentation for details.
     */
    suspend fun count(request: QueryEnhancedRequest): Long {
        return table.query(request).count()
    }

    /**
     * See the API documentation for details.
     */
    suspend fun save(item: T) {
        table.putItem(item).await()
    }

    /**
     * See the API documentation for details.
     *
     * ```kotlin
     * val count = repository.saveAll(items).count()
     * check(count >= 1)
     * ```
     */
    fun saveAll(items: Collection<T>): Flow<BatchWriteResult> {
        return client.batchWriteItems(itemClass, table, items = items)
    }

    /**
     * See the API documentation for details.
     */
    suspend fun update(item: T): T? {
        return table.updateItem(item).await()
    }

    /**
     * See the API documentation for details.
     */
    suspend fun delete(item: T): T? {
        return table.deleteItem(item.key).await()
    }

    /**
     * See the API documentation for details.
     */
    suspend fun delete(key: Key): T? {
        return table.deleteItem(key).await()
    }

    /**
     * See the API documentation for details.
     *
     * See the API documentation for details.
     */
    fun deleteAll(items: Iterable<T>): Flow<T> {
        return items.asFlow()
            .async { item ->
                delete(item)
            }
            .mapNotNull { it }
    }

    /**
     * See the API documentation for details.
     */
    fun deleteAllByKeys(keys: Iterable<Key>): Flow<T> {
        return keys.asFlow()
            .async { key ->
                delete(key)
            }
            .mapNotNull { it }
    }
}
