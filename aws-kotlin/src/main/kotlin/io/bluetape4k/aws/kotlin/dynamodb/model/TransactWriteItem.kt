package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.ConditionCheck
import aws.sdk.kotlin.services.dynamodb.model.Delete
import aws.sdk.kotlin.services.dynamodb.model.Put
import aws.sdk.kotlin.services.dynamodb.model.TransactWriteItem
import aws.sdk.kotlin.services.dynamodb.model.Update

/**
 * Creates a DynamoDB [TransactWriteItem] from a [Put] object.
 *
 * ## Behavior and contract
 * - Sets [put] directly on the `put` field of [TransactWriteItem].
 *
 * ```kotlin
 * val item = transactWriteItemOf(putOf("users", mapOf("id" to AttributeValue.S("u1"))))
 * // item.put?.tableName == "users"
 * ```
 *
 * @param put [Put] object that defines the write operation.
 */
fun transactWriteItemOf(put: Put): TransactWriteItem =
    TransactWriteItem {
        this.put = put
    }

/**
 * Creates a DynamoDB [TransactWriteItem] from a table name and item.
 *
 * ## Behavior and contract
 * - Calls [putOf] internally, creates a [Put], then wraps it as [TransactWriteItem].
 * - Throws `IllegalArgumentException` when [name] is blank.
 *
 * ```kotlin
 * val item = transactWriteItemOf("users", mapOf("id" to AttributeValue.S("u1"))) {}
 * // item.put?.tableName == "users"
 * ```
 *
 * @param name DynamoDB table name to store the item in. Blank values throw.
 * @param item [AttributeValue] attribute map for the item to store.
 */
inline fun transactWriteItemOf(
    name: String,
    item: Map<String, AttributeValue> = emptyMap(),
    crossinline putBuilder: Put.Builder.() -> Unit,
): TransactWriteItem =
    transactWriteItemOf(putOf(name, item, putBuilder))


/**
 * Builds a DynamoDB [TransactWriteItem] with a DSL block.
 *
 * ## Behavior and contract
 * - Specify one of [conditionCheck], [delete], [put], or [update] to configure a transaction write operation.
 * - Creates an empty [TransactWriteItem] when all values are null.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val item = transactWriteItemOf(
 *     put = putOf("users", mapOf("id" to AttributeValue.S("u1")))
 * )
 * // item.put?.tableName == "users"
 * ```
 *
 * @param conditionCheck condition-check operation.
 * @param delete delete operation.
 * @param put put operation.
 * @param update update operation.
 */
fun transactWriteItemOf(
    conditionCheck: ConditionCheck? = null,
    delete: Delete? = null,
    put: Put? = null,
    update: Update? = null,
    builder: TransactWriteItem.Builder.() -> Unit = {},
): TransactWriteItem = TransactWriteItem {
    this.conditionCheck = conditionCheck
    this.put = put
    this.update = update
    this.delete = delete

    builder()
}
