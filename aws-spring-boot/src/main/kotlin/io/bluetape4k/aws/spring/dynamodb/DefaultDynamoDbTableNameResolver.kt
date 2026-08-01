package io.bluetape4k.aws.spring.dynamodb

/**
 * 논리 테이블 이름 앞에 구성된 접두사를 붙이는 기본 [DynamoDbTableNameResolver]입니다.
 */
class DefaultDynamoDbTableNameResolver(
    private val tablePrefix: String = "",
): DynamoDbTableNameResolver {

    override fun resolve(tableName: String): String =
        tablePrefix + tableName
}
