package io.bluetape4k.aws.dynamodb.schema

import io.bluetape4k.aws.dynamodb.model.DynamoDbEntity
import software.amazon.awssdk.enhanced.dynamodb.TableSchema

/**
 * See the API documentation for details.
 *
 * ```kotlin
 * val schema = getTableSchema<FoodDocument>()
 * ```
 *
 * @return Return value.
 */
inline fun <reified T: DynamoDbEntity> getTableSchema(): TableSchema<T> =
    TableSchema.fromClass(T::class.java)
