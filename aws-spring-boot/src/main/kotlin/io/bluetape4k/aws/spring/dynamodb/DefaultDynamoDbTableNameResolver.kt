package io.bluetape4k.aws.spring.dynamodb

/**
 * 설정된 prefix를 논리 테이블 이름 앞에 붙이는 기본 [DynamoDbTableNameResolver].
 */
class DefaultDynamoDbTableNameResolver(
    private val tablePrefix: String = "",
): DynamoDbTableNameResolver {

    override fun resolve(tableName: String): String =
        tablePrefix + tableName
}
