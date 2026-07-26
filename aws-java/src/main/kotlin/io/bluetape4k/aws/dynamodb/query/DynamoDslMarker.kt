package io.bluetape4k.aws.dynamodb.query

/**
 * Marker annotation that restricts DynamoDB Query DSL scopes.
 *
 * ```kotlin
 * @DynamoDslMarker
 * class QueryBuilder {
 *     var tableName: String = ""
 * }
 * // Prevent mixed nested scopes inside DSL functions
 * ```
 */
@DslMarker
annotation class DynamoDslMarker
