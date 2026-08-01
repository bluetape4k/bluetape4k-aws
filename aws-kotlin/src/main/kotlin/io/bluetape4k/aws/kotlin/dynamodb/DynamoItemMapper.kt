package io.bluetape4k.aws.kotlin.dynamodb

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.WriteRequest

/**
 * 엔티티를 DynamoDB Item 속성 맵으로 변환하는 매퍼 인터페이스입니다.
 *
 * ## 동작/계약
 * - `fun interface`이므로 람다로 바로 인스턴스를 생성할 수 있다.
 * - [mapToDynamoItem] 구현에서 엔티티의 각 필드를 `AttributeValue`로 변환해 반환한다.
 *
 * ```kotlin
 * val mapper = DynamoItemMapper<Order> { order ->
 *     mapOf("id" to AttributeValue.S(order.id), "total" to AttributeValue.N(order.total.toString()))
 * }
 * ```
 */
fun interface DynamoItemMapper<T: Any> {

    /**
     * [item] 엔티티를 DynamoDB 속성 맵으로 변환합니다.
     *
     * @return 컬럼명 → [AttributeValue] 매핑
     */
    fun mapToDynamoItem(item: T): Map<String, AttributeValue>
}

/**
 * DynamoDB 항목 속성 맵을 애플리케이션 타입으로 읽습니다.
 *
 * 계약:
 * - 구현은 명시적이고 결정론적이어야 합니다. 이 인터페이스는 리플렉션이나 프리뷰 AWS Kotlin DynamoDB 매퍼를 사용하지 않습니다.
 * - 누락되었거나 잘못된 속성은 일부만 초기화된 도메인 객체를 반환하지 말고 빠르게 실패해야 합니다.
 *
 * ```kotlin
 * val reader = DynamoItemReader<Order> { item ->
 *     Order(id = item.getValue("id").asS(), total = item.getValue("total").asN().toBigDecimal())
 * }
 * ```
 */
fun interface DynamoItemReader<T: Any> {

    /**
     * DynamoDB 항목 속성 맵 하나를 [T]로 변환합니다.
     */
    fun readDynamoItem(item: Map<String, AttributeValue>): T
}

/**
 * Iterable의 엔티티들을 DynamoDB 쓰기 (Put) 작업 요청인 [WriteRequest]의 컬렉션으로 변환합니다.
 *
 * ```kotlin
 * val writeRequests = items.buildWriteRequests(mapper)
 * ```
 */
fun <T: Any> Iterable<T>.buildWritePutRequests(mapper: DynamoItemMapper<T>): List<WriteRequest> {
    return map {
        WriteRequest {
            putRequest {
                item = mapper.mapToDynamoItem(it)
            }
        }
    }
}

/**
 * Iterable의 엔티티들을 DynamoDB 삭제 (Delete) 작업 요청인 [WriteRequest]의 컬렉션으로 변환합니다.
 *
 * ```kotlin
 * val writeRequests = items.buildWriteDeleteRequests(mapper)
 * ```
 */
fun <T: Any> Iterable<T>.buildWriteDeleteRequests(keySelector: DynamoItemMapper<T>): List<WriteRequest> {
    return this.map {
        WriteRequest {
            deleteRequest {
                key = keySelector.mapToDynamoItem(it)
            }
        }
    }
}
