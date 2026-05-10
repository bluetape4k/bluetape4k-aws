package io.bluetape4k.aws.spring.dynamodb

import kotlinx.coroutines.flow.Flow
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest

/**
 * Spring 애플리케이션에서 사용하는 Coroutines 기반 DynamoDB Repository 계약.
 */
interface CoroutinesDynamoDbRepository<T: Any, ID: Any> {

    /**
     * Repository가 사용하는 논리 테이블 이름.
     */
    val tableName: String

    /**
     * Repository가 사용하는 Enhanced Async Table.
     */
    val table: DynamoDbAsyncTable<T>

    /**
     * 아이템을 저장하고 저장한 아이템을 반환합니다.
     */
    suspend fun save(item: T): T

    /**
     * 식별자로 아이템을 조회합니다.
     */
    suspend fun findById(id: ID): T?

    /**
     * 식별자에 해당하는 아이템 존재 여부를 반환합니다.
     */
    suspend fun existsById(id: ID): Boolean

    /**
     * 식별자에 해당하는 아이템을 삭제하고 삭제된 아이템을 반환합니다.
     */
    suspend fun deleteById(id: ID): T?

    /**
     * 아이템의 키로 삭제하고 삭제된 아이템을 반환합니다.
     */
    suspend fun delete(item: T): T?

    /**
     * 아이템을 업데이트하고 DynamoDB가 반환한 최신 아이템을 반환합니다.
     */
    suspend fun update(item: T): T?

    /**
     * 테이블 scan 결과를 [Flow]로 반환합니다.
     */
    fun scan(builder: ScanEnhancedRequest.Builder.() -> Unit = {}): Flow<T>

    /**
     * 테이블 query 결과를 [Flow]로 반환합니다.
     */
    fun query(
        queryConditional: QueryConditional,
        builder: QueryEnhancedRequest.Builder.() -> Unit = {},
    ): Flow<T>

    /**
     * 보조 인덱스 query 결과를 [Flow]로 반환합니다.
     */
    fun queryIndex(
        indexName: String,
        queryConditional: QueryConditional,
        builder: QueryEnhancedRequest.Builder.() -> Unit = {},
    ): Flow<T>
}
