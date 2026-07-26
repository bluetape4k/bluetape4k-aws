package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.KeySchemaElement
import aws.sdk.kotlin.services.dynamodb.model.KeyType
import io.bluetape4k.support.requireNotBlank

/**
 * Creates a DynamoDB [KeySchemaElement].
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [attributeName] is blank.
 * - [keyType] defaults to [KeyType.Hash], the partition key.
 *
 * ```kotlin
 * val elem = keySchemaElementOf("userId", KeyType.Hash)
 * // elem.attributeName == "userId"
 * // elem.keyType == KeyType.Hash
 * ```
 *
 * @param attributeName attribute name in the key schema. Blank values throw.
 * @param keyType key schema type. Partition key is [KeyType.Hash], sort key is [KeyType.Range].
 */
fun keySchemaElementOf(
    attributeName: String?,
    keyType: KeyType? = KeyType.Hash,
): KeySchemaElement {
    attributeName.requireNotBlank("attributeName")

    return KeySchemaElement {
        this.attributeName = attributeName
        this.keyType = keyType
    }
}

/**
 * Creates a DynamoDB partition-key [KeySchemaElement] using this string as the attribute name.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when the receiver string is blank.
 * - Returns a [KeySchemaElement] fixed to [KeyType.Hash].
 *
 * ```kotlin
 * val pk = "userId".partitionKey()
 * // pk.attributeName == "userId"
 * // pk.keyType == KeyType.Hash
 * ```
 */
fun String.partitionKey(): KeySchemaElement =
    keySchemaElementOf(this, KeyType.Hash)

/**
 * Creates a DynamoDB sort-key [KeySchemaElement] using this string as the attribute name.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when the receiver string is blank.
 * - Returns a [KeySchemaElement] fixed to [KeyType.Range].
 *
 * ```kotlin
 * val sk = "createdAt".sortKey()
 * // sk.attributeName == "createdAt"
 * // sk.keyType == KeyType.Range
 * ```
 */
fun String.sortKey(): KeySchemaElement =
    keySchemaElementOf(this, KeyType.Range)

/**
 * Creates a DynamoDB partition-key [KeySchemaElement] with the given name.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [name] is blank.
 * - Returns a [KeySchemaElement] fixed to [KeyType.Hash].
 *
 * ```kotlin
 * val pk = partitionKeyOf("userId")
 * // pk.attributeName == "userId"
 * // pk.keyType == KeyType.Hash
 * ```
 *
 * @param name partition-key attribute name. Blank values throw.
 */
fun partitionKeyOf(name: String): KeySchemaElement =
    keySchemaElementOf(name, KeyType.Hash)

/**
 * Creates a DynamoDB sort-key [KeySchemaElement] with the given name.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [name] is blank.
 * - Returns a [KeySchemaElement] fixed to [KeyType.Range].
 *
 * ```kotlin
 * val sk = sortKeyOf("createdAt")
 * // sk.attributeName == "createdAt"
 * // sk.keyType == KeyType.Range
 * ```
 *
 * @param name sort-key attribute name. Blank values throw.
 */
fun sortKeyOf(name: String): KeySchemaElement =
    keySchemaElementOf(name, KeyType.Range)
