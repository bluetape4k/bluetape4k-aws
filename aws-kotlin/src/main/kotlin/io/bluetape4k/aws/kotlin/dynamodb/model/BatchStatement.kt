package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.BatchStatementRequest
import aws.sdk.kotlin.services.dynamodb.model.ReturnValuesOnConditionCheckFailure
import io.bluetape4k.support.requireNotBlank

/**
 * Builds a DynamoDB [BatchStatementRequest] with a DSL block. [AttributeValue] parameter overload.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [statement] is blank.
 * - [parameters] are mapped in order as PartiQL binding parameters.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val req = batchStatementRequestOf(
 *     statement = "SELECT * FROM users WHERE id = ?",
 *     parameters = listOf(AttributeValue.S("u1"))
 * )
 * // req.statement == "SELECT * FROM users WHERE id = ?"
 * ```
 *
 * @param statement PartiQL statement to execute. Blank values throw.
 * @param parameters PartiQL binding parameter list.
 * @param consistentRead whether to use strongly consistent reads.
 * @param returnValuesOnConditionCheckFailure item return settings for condition check failures.
 */
@JvmName("batchStatementRequestOfAttributeValue")
inline fun batchStatementRequestOf(
    statement: String,
    parameters: List<AttributeValue>? = null,
    consistentRead: Boolean? = null,
    returnValuesOnConditionCheckFailure: ReturnValuesOnConditionCheckFailure? = null,
    crossinline builder: BatchStatementRequest.Builder.() -> Unit = {},
): BatchStatementRequest {
    statement.requireNotBlank("statement")

    return BatchStatementRequest {
        this.statement = statement
        this.parameters = parameters
        this.consistentRead = consistentRead
        this.returnValuesOnConditionCheckFailure = returnValuesOnConditionCheckFailure

        builder()
    }
}

/**
 * Builds a DynamoDB [BatchStatementRequest] with a DSL block. Any? parameter overload.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [statement] is blank.
 * - Each element in [parameters] is converted into [AttributeValue] through [toAttributeValue].
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val req = batchStatementRequestOf(
 *     statement = "SELECT * FROM users WHERE id = ?",
 *     parameters = listOf("u1")
 * )
 * // req.parameters?.first() == AttributeValue.S("u1")
 * ```
 *
 * @param statement PartiQL statement to execute. Blank values throw.
 * @param parameters PartiQL binding parameter list. Converted to [AttributeValue] automatically.
 * @param consistentRead whether to use strongly consistent reads.
 * @param returnValuesOnConditionCheckFailure item return settings for condition check failures.
 */
@JvmName("batchStatementRequestOfAny")
inline fun batchStatementRequestOf(
    statement: String,
    parameters: List<Any?>? = null,
    consistentRead: Boolean? = null,
    returnValuesOnConditionCheckFailure: ReturnValuesOnConditionCheckFailure? = null,
    crossinline builder: BatchStatementRequest.Builder.() -> Unit = {},
): BatchStatementRequest {
    statement.requireNotBlank("statement")

    return BatchStatementRequest {
        this.statement = statement
        this.parameters = parameters?.map { it.toAttributeValue() }
        this.consistentRead = consistentRead
        this.returnValuesOnConditionCheckFailure = returnValuesOnConditionCheckFailure

        builder()
    }
}
