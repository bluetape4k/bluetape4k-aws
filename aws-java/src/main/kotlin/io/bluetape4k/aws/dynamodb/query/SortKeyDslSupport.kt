package io.bluetape4k.aws.dynamodb.query

import java.io.Serializable

/**
 * Class that supports `SortKey` in the DynamoDB DSL.
 *
 * [comparisonOperator] is used to choose the [software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional]
 * creation branch in the Enhanced Query DSL.
 *
 * ```kotlin
 * val sk = SortKey(sortKeyName = "createdAt", comparisonOperator = Equals("2026-01-01"))
 * // sk.sortKeyName == "createdAt"
 * ```
 */
data class SortKey(
    val sortKeyName: String = "sortKey",
    val comparisonOperator: DynamoComparator,
): ComparableBuilder, Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Builder class for creating a [SortKey].
 *
 * ```kotlin
 * val builder = SortKeyBuilder("createdAt")
 * builder eq "2026-01-01"
 * val sk = builder.build()
 * // sk.sortKeyName == "createdAt"
 * ```
 */
class SortKeyBuilder(val keyName: String = "sortKey") {
    var comparator: DynamoComparator? = null

    /** Creates a [SortKey] from the configured comparison operator. */
    fun build(): SortKey {
        // WHY: provide a clear error when comparator is missing, instead of using a non-null assertion.
        val cmp = checkNotNull(comparator) { "SortKeyBuilder: comparator must be set via 'eq', 'between', etc. before build()" }
        return SortKey(keyName, cmp)
    }
}

/** Sets the sort key to a `BETWEEN` comparison expression. */
fun SortKeyBuilder.between(values: Pair<Any, Any>) {
    comparator = Between(values.first, values.second)
}

/** Sets the sort key to an `EQ` comparison expression. */
infix fun SortKeyBuilder.eq(value: Any) {
    comparator = Equals(value)
}
