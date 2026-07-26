package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.AttributeDefinition
import aws.sdk.kotlin.services.dynamodb.model.ScalarAttributeType
import io.bluetape4k.support.requireNotBlank

/**
 * Creates a DynamoDB [AttributeDefinition] from an attribute name and type.
 *
 * ## Behavior and contract
 * - Throws `IllegalArgumentException` when [name] is blank.
 *
 * ```kotlin
 * val def = attributeDefinitionOf("userId", ScalarAttributeType.S)
 * // def.attributeName == "userId", def.attributeType == ScalarAttributeType.S
 * ```
 *
 * @throws IllegalArgumentException if [name] is blank.
 */
fun attributeDefinitionOf(
    name: String,
    type: ScalarAttributeType,
): AttributeDefinition {
    name.requireNotBlank("name")

    return AttributeDefinition {
        attributeName = name
        attributeType = type
    }
}

fun numberAttrDefinitionOf(name: String): AttributeDefinition =
    attributeDefinitionOf(name, ScalarAttributeType.N)

fun stringAttrDefinitionOf(name: String): AttributeDefinition =
    attributeDefinitionOf(name, ScalarAttributeType.S)

fun binaryAttrDefinitionOf(name: String): AttributeDefinition =
    attributeDefinitionOf(name, ScalarAttributeType.B)

fun String.stringAttributeDefinition(): AttributeDefinition = stringAttrDefinitionOf(this)
fun String.numberAttributeDefinition(): AttributeDefinition = numberAttrDefinitionOf(this)
fun String.binaryAttributeDefinition(): AttributeDefinition = binaryAttrDefinitionOf(this)
