package io.bluetape4k.aws.dynamodb.query

import io.bluetape4k.aws.dynamodb.model.Expression
import io.bluetape4k.aws.dynamodb.model.toAttributeValue
import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import software.amazon.awssdk.enhanced.dynamodb.Expression
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import java.io.Serializable

/**
 * Intermediate result object for converting the filter DSL to an AWS Enhanced Expression.
 *
 * @property expressionAttributeValues Expression value binding map.
 * @property filterExpression DynamoDB filter expression string.
 * @property expressionAttributeNames Expression name binding map.
 */
data class FilterRequestProperties(
    val expressionAttributeValues: MutableMap<String, AttributeValue>,
    val filterExpression: String,
    val expressionAttributeNames: MutableMap<String, String>,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Converts [FilterRequestProperties] to an AWS Enhanced Client [Expression].
 *
 * ```kotlin
 * val expression = requestProperties.toExpression()
 * check(expression.expression().isNotBlank())
 * ```
 */
fun FilterRequestProperties.toExpression(): Expression = Expression {
    expression(filterExpression)
    expressionAttributeNames.takeIf { it.isNotEmpty() }?.let { expressionNames(it) }
    expressionAttributeValues.takeIf { it.isNotEmpty() }?.let { expressionValues(it) }
}

/** Marker interface for filter condition tree roots and nodes. */
@DynamoDslMarker
interface FilterQuery

/**
 * Root of a filter tree connected with AND/OR.
 *
 * [getFilterRequestProperties] creates the filter expression string and binding maps in connection order.
 */
@DynamoDslMarker
class RootFilter(val filterConnections: List<FilterConnection>): FilterQuery {

    /**
     * Traverses the filter tree and creates [FilterRequestProperties].
     *
     * The first `filterConnections` entry must start without a connector, and following entries must have an
     * `AND` or `OR` connector.
     */
    fun getFilterRequestProperties(): FilterRequestProperties {
        val expressionAttributeValues = mutableMapOf<String, AttributeValue>()
        val expressionAttributeNames = mutableMapOf<String, String>()
        var filterExpression = ""

        fun filter(condition: FilterQuery) {
            when (condition) {
                is RootFilter -> {
                    val nestedProps = condition.getFilterRequestProperties()
                    filterExpression += "(${nestedProps.filterExpression})"
                    expressionAttributeValues.putAll(nestedProps.expressionAttributeValues)
                    expressionAttributeNames.putAll(nestedProps.expressionAttributeNames)
                }

                is ConcreteFilter -> {
                    val nestedProps = condition.getFilterRequestProperties()
                    filterExpression += nestedProps.filterExpression
                    expressionAttributeValues.putAll(nestedProps.expressionAttributeValues)
                    expressionAttributeNames.putAll(nestedProps.expressionAttributeNames)
                }
            }
        }

        val condition = filterConnections.first().value
        filter(condition)

        filterConnections.drop(1)
            .forEach {
                it.connectionToLeft?.let { booleanConnection: FilterBooleanConnection ->
                    filterExpression += " ${booleanConnection.name} "
                    filter(it.value)
                } ?: error("Non head filters without connection to left")
            }

        return FilterRequestProperties(expressionAttributeValues, filterExpression, expressionAttributeNames)
    }
}

/**
 * Single attribute-based filter condition.
 *
 * Converts the [dynamoFunction] and [comparator] combination to an expression string.
 */
@DynamoDslMarker
class ConcreteFilter(
    val dynamoFunction: DynamoFunction,
    val comparator: DynamoComparator? = null,
): FilterQuery {

    companion object: KLogging() {
        private val alphabets = ('a' until 'z') + ('A' until 'Z')

        private fun toExprAttrName(attributeName: String): String =
            "#" + generateExprAttrName(attributeName)

        private fun toExprAttrValue(attributeName: String): String =
            ":" + generateExprAttrName(attributeName)

        private fun generateExprAttrName(attributeName: String): String =
            attributeName.filter { it in alphabets } + nonce()

        private fun nonce(length: Int = 5): String =
            "__" + Base58.randomString(length)
    }

    /**
     * Converts a single filter to [FilterRequestProperties].
     *
     * Based on `DynamoDbQueryDslTest`, the converted result composes the expression string, name bindings,
     * and value bindings together.
     */
    fun getFilterRequestProperties(): FilterRequestProperties {
        val expressionAttributeValues = mutableMapOf<String, AttributeValue>()
        val expressionAttributeNames = mutableMapOf<String, String>()
        var filterExpression = ""

        when (dynamoFunction) {
            is Attribute -> {
                val exprAttrName = toExprAttrName(dynamoFunction.attributeName)
                filterExpression += exprAttrName
                expressionAttributeNames[exprAttrName] = dynamoFunction.attributeName

                fun singleValueComparator(operator: String, comparator: SingleValueDynamoComparator) {
                    val exprAttrValue = toExprAttrValue(dynamoFunction.attributeName)
                    filterExpression += " $operator $exprAttrValue"
                    expressionAttributeValues[exprAttrValue] = comparator.right.toAttributeValue()
                }

                when (comparator) {
                    is Equals           -> singleValueComparator("=", comparator)
                    is NotEquals        -> singleValueComparator("<>", comparator)
                    is GreaterThan      -> singleValueComparator(">", comparator)
                    is GreaterThanOrEquals -> singleValueComparator(">=", comparator)
                    is LessThan         -> singleValueComparator("<", comparator)
                    is LessThanOrEquals -> singleValueComparator("<=", comparator)
                    is Between          -> {
                        val leftExprAttrValue = toExprAttrValue(dynamoFunction.attributeName + "left")
                        val rightExprAttrValue = toExprAttrValue(dynamoFunction.attributeName + "right")

                        filterExpression += " BETWEEN $leftExprAttrValue AND $rightExprAttrValue"
                        expressionAttributeValues[leftExprAttrValue] = comparator.left.toAttributeValue()
                        expressionAttributeValues[rightExprAttrValue] = comparator.right.toAttributeValue()
                    }

                    is InList           -> {
                        val attrValues = comparator.right.joinToString {
                            toExprAttrValue(dynamoFunction.attributeName).apply {
                                expressionAttributeValues[this] = it.toAttributeValue()
                            }
                        }

                        filterExpression += " IN ($attrValues)"
                    }
                }
            }

            is AttributeExists -> {
                val exprAttrName = toExprAttrName(dynamoFunction.attributeName)
                filterExpression += " attribute_exists($exprAttrName)"
                expressionAttributeNames[exprAttrName] = dynamoFunction.attributeName
            }

            else         -> {
                log.warn { "Not supported DynamoFunction: $dynamoFunction" }
            }
        }

        return FilterRequestProperties(expressionAttributeValues, filterExpression, expressionAttributeNames)
    }
}

/** Represents a connection unit such as `AND X` or `OR (Y AND Z)`. */
data class FilterConnection(
    val value: FilterQuery,
    val connectionToLeft: FilterBooleanConnection? = null,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Conditional connection operator. */
enum class FilterBooleanConnection {
    AND,
    OR
}

/** Marker interface for filter functions such as attributes and function calls. */
interface DynamoFunction: Serializable

/** Specifies an attribute-based filter target. */
data class Attribute(val attributeName: String): DynamoFunction {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Specifies the `attribute_exists(name)` filter function. */
data class AttributeExists(val attributeName: String): DynamoFunction {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Common contract for filter DSL builders. */
@DynamoDslMarker
interface FilterQueryBuilder {
    fun build(): FilterQuery
}

/** Builder for a single [ConcreteFilter]. */
@DynamoDslMarker
class ConcreteFilterBuilder: FilterQueryBuilder {
    var dynamoFunction: DynamoFunction? = null
    var comparator: DynamoComparator? = null

    override fun build(): FilterQuery {
        // WHY: fail with a clear message when the DSL builder has no configured dynamoFunction.
        val function = checkNotNull(dynamoFunction) { "dynamoFunction must be set before building filter" }
        return ConcreteFilter(function, comparator)
    }
}

/** Sets the `=` comparison operator. */
infix fun ConcreteFilterBuilder.eq(value: Any) {
    comparator = Equals(value)
}

/** Sets the `<>` comparison operator. */
infix fun ConcreteFilterBuilder.ne(value: Any) {
    comparator = NotEquals(value)
}

/** Sets the `>` comparison operator. */
infix fun ConcreteFilterBuilder.gt(value: Any) {
    comparator = GreaterThan(value)
}

/** Sets the `>=` comparison operator. */
infix fun ConcreteFilterBuilder.ge(value: Any) {
    comparator = GreaterThanOrEquals(value)
}

/** Sets the `<` comparison operator. */
infix fun ConcreteFilterBuilder.lt(value: Any) {
    comparator = LessThan(value)
}

/** Sets the `<=` comparison operator. */
infix fun ConcreteFilterBuilder.le(value: Any) {
    comparator = LessThanOrEquals(value)
}

/** Sets the `IN` comparison operator. */
infix fun ConcreteFilterBuilder.inList(values: List<Any>) {
    comparator = InList(values)
}

/** Sets the `IN` comparison operator from varargs. */
fun ConcreteFilterBuilder.inList(vararg values: Any) {
    comparator = InList(values.toList())
}

/**
 * Root builder for compound conditions connected with AND/OR.
 *
 * ```kotlin
 * val root = RootFilterBuilder().apply {
 *     attribute("status") { eq("OPEN") } and attributeExists("updatedAt")
 * }.build()
 *
 * check(root.getFilterRequestProperties().filterExpression.isNotBlank())
 * ```
 */
@DynamoDslMarker
class RootFilterBuilder: FilterQueryBuilder {

    var currentFilter: FilterQuery? = null
    var filterQueries = mutableListOf<FilterConnection>()

    override fun build(): RootFilter {
        filterQueries.requireNotEmpty("filterQueries")
        return RootFilter(filterQueries)
    }

    infix fun and(setup: RootFilterBuilder.() -> Unit): RootFilterBuilder = apply {
        val value = RootFilterBuilder().apply(setup)
        filterQueries.add(FilterConnection(value.build(), FilterBooleanConnection.AND))
    }

    infix fun or(block: RootFilterBuilder.() -> Unit): RootFilterBuilder = apply {
        val value = RootFilterBuilder().apply(block)
        filterQueries.add(FilterConnection(value.build(), FilterBooleanConnection.OR))
    }

    @Suppress("UNUSED_PARAMETER")
    infix fun and(value: RootFilterBuilder): RootFilterBuilder = apply {
        // WHY: provide a clear error when currentFilter is null and cannot be connected with and/or.
        val filter = checkNotNull(currentFilter) { "currentFilter must be set before connecting with AND" }
        filterQueries.add(FilterConnection(filter, FilterBooleanConnection.AND))
    }

    @Suppress("UNUSED_PARAMETER")
    infix fun or(value: RootFilterBuilder): RootFilterBuilder = apply {
        val filter = checkNotNull(currentFilter) { "currentFilter must be set before connecting with OR" }
        filterQueries.add(FilterConnection(filter, FilterBooleanConnection.OR))
    }
}

/**
 * Adds an attribute filter.
 *
 * The first call is registered as the root condition, and later calls are stored in `currentFilter` for `and`/`or`
 * connections.
 */
inline fun RootFilterBuilder.attribute(
    value: String,
    builder: ConcreteFilterBuilder.() -> Unit = {},
): RootFilterBuilder = apply {
    val attributeName = value.requireNotBlank("attribute")
    val concreteFilter = ConcreteFilterBuilder().apply(builder)
    concreteFilter.dynamoFunction = Attribute(attributeName)

    if (filterQueries.isEmpty()) {
        filterQueries.add(FilterConnection(concreteFilter.build(), null))
    } else {
        currentFilter = concreteFilter.build()
    }
}

/**
 * Registers an `attribute_exists(name)` filter as the current condition.
 */
infix fun RootFilterBuilder.attributeExists(value: String): RootFilterBuilder = apply {
    this.currentFilter = ConcreteFilter(AttributeExists(value.requireNotBlank("attributeExists")))
}
