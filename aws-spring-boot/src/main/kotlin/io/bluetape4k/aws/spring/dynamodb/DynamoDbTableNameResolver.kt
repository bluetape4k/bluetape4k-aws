package io.bluetape4k.aws.spring.dynamodb

/**
 * 논리 DynamoDB 테이블 이름을 물리 테이블 이름으로 해석합니다.
 */
fun interface DynamoDbTableNameResolver {

    /**
     * 현재 구성과 런타임 환경에 맞는 물리 테이블 이름을 반환합니다.
     */
    fun resolve(tableName: String): String
}
