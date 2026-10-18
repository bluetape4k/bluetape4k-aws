package io.bluetape4k.aws.dynamodb.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType

inline fun attributeDefinition(
    builder: AttributeDefinition.Builder.() -> Unit,
): AttributeDefinition =
    AttributeDefinition.builder().apply(builder).build()

fun attributeDefinitionOf(
    name: String,
    type: ScalarAttributeType,
): AttributeDefinition = attributeDefinition {
    attributeName(name.requireNotBlank("name"))
    attributeType(type)
}
