package io.bluetape4k.aws.dynamodb.query

import io.bluetape4k.aws.dynamodb.model.toAttributeValue
import software.amazon.awssdk.services.dynamodb.model.ComparisonOperator
import software.amazon.awssdk.services.dynamodb.model.Condition

/**
 * Common contract for converting DynamoDB comparison conditions to [Condition].
 */
@DynamoDslMarker
interface DynamoComparator {
    /** Converts a DSL comparison expression to an AWS SDK [Condition]. */
    fun toCondition(): Condition
}

/**
 * Contract for comparison operators with a single right-hand operand.
 */
@DynamoDslMarker
interface SingleValueDynamoComparator: DynamoComparator {
    /** Right-hand value of the comparison expression. */
    val right: Any
}

/** Marker interface for comparison expressions used by `SortKey`. */
@DynamoDslMarker
interface ComparableBuilder

/**
 * Creates a [Condition] with the [Condition.Builder] DSL.
 *
 * ```kotlin
 * val condition = Condition {
 *     comparisonOperator(ComparisonOperator.EQ)
 * }
 *
 * check(condition.comparisonOperator() == ComparisonOperator.EQ)
 * ```
 */
inline fun Condition(builder: Condition.Builder.() -> Unit): Condition {
    return Condition.builder().apply(builder).build()
}

/** `BEGINS_WITH` comparison expression. */
@DynamoDslMarker
class BeginsWith(override val right: Any): SingleValueDynamoComparator {
    override fun toCondition(): Condition = Condition {
        comparisonOperator(ComparisonOperator.BEGINS_WITH)
        attributeValueList(right.toAttributeValue())
    }
}

/** `EQ` comparison expression. */
@DynamoDslMarker
class Equals(override val right: Any): SingleValueDynamoComparator {
    override fun toCondition(): Condition = Condition {
        comparisonOperator(ComparisonOperator.EQ)
        attributeValueList(right.toAttributeValue())
    }
}

/** `NE` comparison expression. */
@DynamoDslMarker
class NotEquals(override val right: Any): SingleValueDynamoComparator {
    override fun toCondition(): Condition = Condition {
        comparisonOperator(ComparisonOperator.NE)
        attributeValueList(right.toAttributeValue())
    }
}

/** `GT` comparison expression. */
@DynamoDslMarker
class GreaterThan(override val right: Any): SingleValueDynamoComparator {
    override fun toCondition(): Condition = Condition {
        comparisonOperator(ComparisonOperator.GT)
        attributeValueList(right.toAttributeValue())
    }
}

/** `GE` comparison expression. */
@DynamoDslMarker
class GreaterThanOrEquals(override val right: Any): SingleValueDynamoComparator {
    override fun toCondition(): Condition = Condition {
        comparisonOperator(ComparisonOperator.GE)
        attributeValueList(right.toAttributeValue())
    }
}

/** `LT` comparison expression. */
@DynamoDslMarker
class LessThan(override val right: Any): SingleValueDynamoComparator {
    override fun toCondition(): Condition = Condition {
        comparisonOperator(ComparisonOperator.LT)
        attributeValueList(right.toAttributeValue())
    }
}

/** `LE` comparison expression. */
@DynamoDslMarker
class LessThanOrEquals(override val right: Any): SingleValueDynamoComparator {
    override fun toCondition(): Condition = Condition {
        comparisonOperator(ComparisonOperator.LE)
        attributeValueList(right.toAttributeValue())
    }
}

/** `IN` comparison expression. */
@DynamoDslMarker
class InList(override val right: List<Any>): SingleValueDynamoComparator {
    override fun toCondition(): Condition = Condition {
        comparisonOperator(ComparisonOperator.IN)
        attributeValueList(right.toAttributeValue())
    }
}

/** `BETWEEN` comparison expression. */
@DynamoDslMarker
class Between(val left: Any, val right: Any): DynamoComparator {
    override fun toCondition(): Condition = Condition {
        comparisonOperator(ComparisonOperator.BETWEEN)
        attributeValueList(left.toAttributeValue(), right.toAttributeValue())
    }
}
