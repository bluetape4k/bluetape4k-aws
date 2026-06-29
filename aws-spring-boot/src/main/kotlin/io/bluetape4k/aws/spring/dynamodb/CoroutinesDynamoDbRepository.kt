package io.bluetape4k.aws.spring.dynamodb

import kotlinx.coroutines.flow.Flow
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest

/**
 * Coroutine-based DynamoDB repository contract for Spring applications.
 */
interface CoroutinesDynamoDbRepository<T: Any, ID: Any> {

    /**
     * Logical table name used by the repository.
     */
    val tableName: String

    /**
     * Enhanced async table used by the repository.
     */
    val table: DynamoDbAsyncTable<T>

    /**
     * Saves an item and returns the saved item.
     */
    suspend fun save(item: T): T

    /**
     * Finds an item by its identifier.
     */
    suspend fun findById(id: ID): T?

    /**
     * Returns whether an item exists for the identifier.
     */
    suspend fun existsById(id: ID): Boolean

    /**
     * Deletes an item by identifier and returns the deleted item.
     */
    suspend fun deleteById(id: ID): T?

    /**
     * Deletes an item by its key and returns the deleted item.
     */
    suspend fun delete(item: T): T?

    /**
     * Updates an item and returns the latest item returned by DynamoDB.
     */
    suspend fun update(item: T): T?

    /**
     * Returns table scan results as a [Flow].
     */
    fun scan(builder: ScanEnhancedRequest.Builder.() -> Unit = {}): Flow<T>

    /**
     * Returns table query results as a [Flow].
     */
    fun query(
        queryConditional: QueryConditional,
        builder: QueryEnhancedRequest.Builder.() -> Unit = {},
    ): Flow<T>

    /**
     * Returns secondary-index query results as a [Flow].
     */
    fun queryIndex(
        indexName: String,
        queryConditional: QueryConditional,
        builder: QueryEnhancedRequest.Builder.() -> Unit = {},
    ): Flow<T>
}
