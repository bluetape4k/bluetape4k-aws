package io.bluetape4k.aws.dynamodb.query

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotNull
import software.amazon.awssdk.services.dynamodb.model.QueryRequest

/**
 * Creates a [QueryRequest] with a DSL.
 *
 * ```kotlin
 * val request = queryRequest {
 *     tableName = "orders"
 *     primaryKey("pk") { eq("order#1") }
 * }
 *
 * check(request.keyConditions().containsKey("pk"))
 * ```
 */
inline fun queryRequest(builder: QueryRequestBuilderDSL.() -> Unit): QueryRequest =
    QueryRequestBuilderDSL().apply(builder).build()

/** Builder that stores DSL state for creating a [QueryRequest]. */
@DynamoDslMarker
class QueryRequestBuilderDSL {
    var tableName: String? = null
    var primaryKey: PrimaryKey? = null
    var sortKey: SortKey? = null
    var filtering: RootFilter? = null

    /**
     * Converts the current DSL state to a [QueryRequest].
     *
     * Throws when `tableName` or `primaryKey` is missing.
     */
    fun build(): QueryRequest {
        val table = tableName.requireNotBlank("tableName")
        val pk = primaryKey.requireNotNull("primaryKey")

        val request = QueryRequest.builder().tableName(table)

        val sk = sortKey
        if (sk == null) {
            request.keyConditions(mapOf(pk.keyName to pk.equals.toCondition()))
        } else {
            request.keyConditions(
                mapOf(
                    pk.keyName to pk.equals.toCondition(),
                    sk.sortKeyName to sk.comparisonOperator.toCondition()
                )
            )
        }

        filtering?.let { filter ->
            val props = filter.getFilterRequestProperties()

            request.filterExpression(props.filterExpression)
            if (props.expressionAttributeNames.isNotEmpty()) {
                request.expressionAttributeNames(props.expressionAttributeNames)
            }
            if (props.expressionAttributeValues.isNotEmpty()) {
                request.expressionAttributeValues(props.expressionAttributeValues)
            }
        }

        return request.build()
    }
}

/** Configures the partition-key condition. */
inline fun QueryRequestBuilderDSL.primaryKey(
    keyName: String,
    builder: PrimaryKeyBuilder.() -> Unit,
) {
    primaryKey = PrimaryKeyBuilder(keyName).apply(builder).build()
}

/** Configures the sort-key condition. */
inline fun QueryRequestBuilderDSL.sortKey(
    keyName: String,
    builder: SortKeyBuilder.() -> Unit,
) {
    sortKey = SortKeyBuilder(keyName).apply(builder).build()
}

/** Configures the filter condition. */
inline fun QueryRequestBuilderDSL.filtering(
    builder: RootFilterBuilder.() -> Unit,
) {
    filtering = RootFilterBuilder().apply(builder).build()
}
