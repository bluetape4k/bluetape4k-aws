package io.bluetape4k.aws.spring.dynamodb

/**
 * 논리 DynamoDB 테이블 이름을 실제 테이블 이름으로 변환합니다.
 */
fun interface DynamoDbTableNameResolver {

    /**
     * 설정과 실행 환경을 반영한 실제 테이블 이름을 반환합니다.
     */
    fun resolve(tableName: String): String
}
