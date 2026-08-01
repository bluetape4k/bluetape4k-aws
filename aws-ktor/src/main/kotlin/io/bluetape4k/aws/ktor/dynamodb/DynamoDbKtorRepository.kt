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
 * DynamoDB 테이블 하나를 위한 코루틴 리포지토리 파사드입니다.
 *
 * 계약:
 * - 명시적인 [mapper], [reader], [keyMapper] 함수를 사용하며 리플렉션이나 프리뷰 AWS Kotlin DynamoDB 매퍼를 사용하지 않습니다.
 * - 안정적인 v1 작업인 저장, 조회, 삭제, 스캔, 쿼리만 제공합니다.
 * - 고급 갱신 표현식, 배치 읽기, 스키마 검증은 현재 하위 수준 AWS Kotlin SDK API에서 사용합니다.
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
     * PutItem으로 [item]을 저장하고 원본 입력 항목을 반환합니다.
     *
     * PutItem은 기본적으로 저장한 항목을 되돌려주지 않습니다. AWS 응답 메타데이터가 필요하면 [put]을 사용하세요.
     */
    suspend fun save(
        item: T,
        builder: PutItemRequest.Builder.() -> Unit = {},
    ): T {
        put(item, builder)
        return item
    }

    /**
     * PutItem으로 [item]을 저장하고 AWS 응답을 반환합니다.
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
     * 매핑된 DynamoDB 키로 항목 하나를 조회합니다.
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
     * 매핑된 DynamoDB 키로 항목 하나를 삭제합니다.
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
     * 이 테이블을 스캔하고 매핑된 항목을 스트리밍합니다.
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
     * 이 테이블을 쿼리하고 매핑된 항목을 스트리밍합니다.
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
