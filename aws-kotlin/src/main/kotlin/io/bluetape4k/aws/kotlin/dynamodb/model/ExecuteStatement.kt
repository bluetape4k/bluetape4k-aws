package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.ExecuteStatementRequest
import io.bluetape4k.support.requireNotBlank

/**
 * Builds a DynamoDB [ExecuteStatementRequest] with a DSL block. [AttributeValue] parameter overload.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [statement] is blank.
 * - [parameters] are mapped in order as PartiQL binding parameters.
 * - Supports pagination through [nextToken].
 *
 * ```kotlin
 * val req = executeStatementRequestOf(
 *     statement = "SELECT * FROM users WHERE id = ?",
 *     parameters = listOf(AttributeValue.S("u1")),
 *     limit = 10
 * )
 * // req.statement == "SELECT * FROM users WHERE id = ?"
 * // req.limit == 10
 * ```
 *
 * @param statement PartiQL statement to execute. Blank values throw.
 * @param parameters PartiQL binding parameter list.
 * @param consistentRead whether to use strongly consistent reads.
 * @param limit maximum number of items to return.
 * @param nextToken pagination token.
 */
@JvmName("executeStatementRequestOfAttributeValue")
inline fun executeStatementRequestOf(
    statement: String,
    parameters: List<AttributeValue>?,
    consistentRead: Boolean? = null,
    limit: Int? = null,
    nextToken: String? = null,
    crossinline builder: ExecuteStatementRequest.Builder.() -> Unit = {},
): ExecuteStatementRequest {
    statement.requireNotBlank("statement")

    return ExecuteStatementRequest {
        this.statement = statement
        this.parameters = parameters
        this.consistentRead = consistentRead
        this.limit = limit
        this.nextToken = nextToken

        builder()
    }
}

/**
 * Builds a DynamoDB [ExecuteStatementRequest] with a DSL block. Any? parameter overload.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [statement] is blank.
 * - Each element in [parameters] is converted into [AttributeValue] through [toAttributeValueList].
 * - Supports pagination through [nextToken].
 *
 * ```kotlin
 * val req = executeStatementRequestOf(
 *     statement = "SELECT * FROM users WHERE id = ?",
 *     parameters = listOf("u1"),
 *     limit = 10
 * )
 * // req.parameters?.first() == AttributeValue.S("u1")
 * ```
 *
 * @param statement PartiQL statement to execute. Blank values throw.
 * @param parameters PartiQL binding parameter list. Converted to [AttributeValue] automatically.
 * @param consistentRead whether to use strongly consistent reads.
 * @param limit maximum number of items to return.
 * @param nextToken pagination token.
 */
@JvmName("executeStatementRequestOfAny")
inline fun executeStatementRequestOf(
    statement: String,
    parameters: List<Any?>?,
    consistentRead: Boolean? = null,
    limit: Int? = null,
    nextToken: String? = null,
    crossinline builder: ExecuteStatementRequest.Builder.() -> Unit = {},
): ExecuteStatementRequest = executeStatementRequestOf(
    statement,
    parameters?.toAttributeValueList(),
    consistentRead,
    limit,
    nextToken,
    builder,
)
