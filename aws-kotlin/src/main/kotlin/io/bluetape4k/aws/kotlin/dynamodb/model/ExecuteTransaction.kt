package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.ExecuteTransactionRequest
import aws.sdk.kotlin.services.dynamodb.model.ParameterizedStatement
import aws.sdk.kotlin.services.dynamodb.model.ReturnConsumedCapacity
import io.bluetape4k.support.requireNotEmpty

/**
 * Builds a DynamoDB [ExecuteTransactionRequest] with a DSL block.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [transactionStatements] is empty.
 * - [clientRequestToken] is an idempotency token for duplicate request prevention and is omitted when null.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val req = executeTransactionRequestOf(
 *     transactionStatements = listOf(
 *         ParameterizedStatement { statement = "UPDATE users SET name = ? WHERE id = ?" }
 *     )
 * )
 * // req.transactStatements?.size == 1
 * ```
 *
 * @param transactionStatements [ParameterizedStatement] list to execute transactionally. Empty values throw.
 * @param clientRequestToken idempotency token for duplicate request prevention.
 * @param returnConsumedCapacity whether to return consumed capacity details.
 */
inline fun executeTransactionRequestOf(
    transactionStatements: List<ParameterizedStatement>,
    clientRequestToken: String? = null,
    returnConsumedCapacity: ReturnConsumedCapacity? = null,
    crossinline builder: ExecuteTransactionRequest.Builder.() -> Unit = {},
): ExecuteTransactionRequest {
    transactionStatements.requireNotEmpty("transactionStatements")

    return ExecuteTransactionRequest {
        this.transactStatements = transactionStatements
        this.clientRequestToken = clientRequestToken
        this.returnConsumedCapacity = returnConsumedCapacity

        builder()
    }
}
