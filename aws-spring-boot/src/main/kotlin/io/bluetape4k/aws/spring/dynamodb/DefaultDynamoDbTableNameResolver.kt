package io.bluetape4k.aws.spring.dynamodb

/**
 * Default [DynamoDbTableNameResolver] that prepends a configured prefix to logical table names.
 */
class DefaultDynamoDbTableNameResolver(
    private val tablePrefix: String = "",
): DynamoDbTableNameResolver {

    override fun resolve(tableName: String): String =
        tablePrefix + tableName
}
