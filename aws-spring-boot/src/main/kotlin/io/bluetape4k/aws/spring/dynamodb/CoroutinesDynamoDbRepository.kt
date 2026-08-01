package io.bluetape4k.aws.spring.dynamodb

import kotlinx.coroutines.flow.Flow
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest

/**
 * Spring 애플리케이션을 위한 코루틴 기반 DynamoDB 리포지토리 계약입니다.
 */
interface CoroutinesDynamoDbRepository<T: Any, ID: Any> {

    /**
     * 리포지토리가 사용하는 논리 테이블 이름입니다.
     */
    val tableName: String

    /**
     * 리포지토리가 사용하는 Enhanced 비동기 테이블입니다.
     */
    val table: DynamoDbAsyncTable<T>

    /**
     * 항목을 저장하고 저장된 항목을 반환합니다.
     */
    suspend fun save(item: T): T

    /**
     * 식별자로 항목을 조회합니다.
     */
    suspend fun findById(id: ID): T?

    /**
     * 식별자에 해당하는 항목이 있는지 반환합니다.
     */
    suspend fun existsById(id: ID): Boolean

    /**
     * 식별자로 항목을 삭제하고 삭제된 항목을 반환합니다.
     */
    suspend fun deleteById(id: ID): T?

    /**
     * 키로 항목을 삭제하고 삭제된 항목을 반환합니다.
     */
    suspend fun delete(item: T): T?

    /**
     * 항목을 갱신하고 DynamoDB가 반환한 최신 항목을 반환합니다.
     */
    suspend fun update(item: T): T?

    /**
     * 테이블 스캔 결과를 [Flow]로 반환합니다.
     */
    fun scan(builder: ScanEnhancedRequest.Builder.() -> Unit = {}): Flow<T>

    /**
     * 테이블 쿼리 결과를 [Flow]로 반환합니다.
     */
    fun query(
        queryConditional: QueryConditional,
        builder: QueryEnhancedRequest.Builder.() -> Unit = {},
    ): Flow<T>

    /**
     * 보조 인덱스 쿼리 결과를 [Flow]로 반환합니다.
     */
    fun queryIndex(
        indexName: String,
        queryConditional: QueryConditional,
        builder: QueryEnhancedRequest.Builder.() -> Unit = {},
    ): Flow<T>
}
