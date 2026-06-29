package io.bluetape4k.aws.spring.dynamodb

/**
 * Resolves logical DynamoDB table names to physical table names.
 */
fun interface DynamoDbTableNameResolver {

    /**
     * Returns the physical table name for the current configuration and runtime environment.
     */
    fun resolve(tableName: String): String
}
