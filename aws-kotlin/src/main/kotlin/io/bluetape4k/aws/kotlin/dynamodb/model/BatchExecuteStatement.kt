package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.BatchExecuteStatementRequest
import aws.sdk.kotlin.services.dynamodb.model.BatchStatementRequest
import aws.sdk.kotlin.services.dynamodb.model.ReturnConsumedCapacity
import io.bluetape4k.support.ifTrue

/**
 * Builds a DynamoDB [BatchExecuteStatementRequest] with a DSL block. List overload.
 *
 * ## Behavior and contract
 * - Omits the `statements` request field when [statements] is null.
 * - Does not return consumed capacity details when [returnConsumedCapacity] is null.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val req = batchExecutionStatementRequestOf(
 *     returnConsumedCapacity = ReturnConsumedCapacity.Total,
 *     statements = listOf(batchStatementRequestOf("SELECT * FROM users WHERE id = ?", listOf("u1")))
 * )
 * // req.statements?.size == 1
 * ```
 *
 * @param returnConsumedCapacity whether to return consumed capacity details.
 * @param statements PartiQL statements to execute.
 */
@JvmName("batchExecutionStatementRequestOfList")
inline fun batchExecutionStatementRequestOf(
    returnConsumedCapacity: ReturnConsumedCapacity? = null,
    statements: List<BatchStatementRequest>? = null,
    crossinline builder: BatchExecuteStatementRequest.Builder.() -> Unit = {},
): BatchExecuteStatementRequest =
    BatchExecuteStatementRequest {
        returnConsumedCapacity?.let { this.returnConsumedCapacity = it }
        statements?.let { this.statements = it }

        builder()
    }

/**
 * Builds a DynamoDB [BatchExecuteStatementRequest] with a DSL block. Vararg overload.
 *
 * ## Behavior and contract
 * - Omits the `statements` request field when [statements] is empty.
 * - Does not return consumed capacity details when [returnConsumedCapacity] is null.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val stmt = batchStatementRequestOf("SELECT * FROM users WHERE id = ?", listOf("u1"))
 * val req = batchExecutionStatementRequestOf(statements = stmt)
 * // req.statements?.size == 1
 * ```
 *
 * @param returnConsumedCapacity whether to return consumed capacity details.
 * @param statements PartiQL statements to execute.
 */
@JvmName("batchExecutionStatementRequestOfArray")
inline fun batchExecutionStatementRequestOf(
    returnConsumedCapacity: ReturnConsumedCapacity? = null,
    vararg statements: BatchStatementRequest,
    crossinline builder: BatchExecuteStatementRequest.Builder.() -> Unit = {},
): BatchExecuteStatementRequest =
    BatchExecuteStatementRequest {
        returnConsumedCapacity?.let { this.returnConsumedCapacity = it }
        statements.isNotEmpty().ifTrue {
            this.statements = statements.toList()
        }

        builder()
    }
