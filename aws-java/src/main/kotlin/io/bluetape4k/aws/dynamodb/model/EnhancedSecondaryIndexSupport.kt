package io.bluetape4k.aws.dynamodb.model

import software.amazon.awssdk.enhanced.dynamodb.model.EnhancedGlobalSecondaryIndex
import software.amazon.awssdk.enhanced.dynamodb.model.EnhancedLocalSecondaryIndex
import software.amazon.awssdk.services.dynamodb.model.Projection

/**
 * See the API documentation for details.
 *
 * ```kotlin
 * val index = EnhancedGlobalSecondaryIndex {
 *   indexName("indexName")
 *   projection(projection)
 *   // ...
 * }
 * ```
 * @param builder Parameter.
 * @return Return value.
 */
inline fun EnhancedGlobalSecondaryIndex(
    builder: EnhancedGlobalSecondaryIndex.Builder.() -> Unit,
): EnhancedGlobalSecondaryIndex {
    return EnhancedGlobalSecondaryIndex.builder().apply(builder).build()
}

/**
 * See the API documentation for details.
 *
 * ```kotlin
 * val index = enhancedGlobalSecondaryIndexOf("indexName", projection)
 * ```
 *
 * @param indexName Parameter.
 * @param projection Parameter.
 * @return Return value.
 */
fun enhancedGlobalSecondaryIndexOf(
    indexName: String,
    projection: Projection,
): EnhancedGlobalSecondaryIndex = EnhancedGlobalSecondaryIndex {
    indexName(indexName)
    projection(projection)
}

/**
 * See the API documentation for details.
 *
 * ```kotlin
 * val index = EnhancedLocalSecondaryIndex {
 *   indexName("indexName")
 *   projection(projection)
 *   // ...
 * }
 * ```
 * @param builder Parameter.
 * @return Return value.
 */
inline fun EnhancedLocalSecondaryIndex(
    builder: EnhancedLocalSecondaryIndex.Builder.() -> Unit,
): EnhancedLocalSecondaryIndex {
    return EnhancedLocalSecondaryIndex.builder().apply(builder).build()
}

/**
 * See the API documentation for details.
 *
 * ```kotlin
 * val index = enhancedLocalSecondaryIndexOf("indexName", projection)
 * ```
 *
 * @param indexName Parameter.
 * @param projection Parameter.
 * @return Return value.
 */
fun enhancedLocalSecondaryIndexOf(
    indexName: String,
    projection: Projection,
): EnhancedLocalSecondaryIndex = EnhancedLocalSecondaryIndex {
    indexName(indexName)
    projection(projection)
}
