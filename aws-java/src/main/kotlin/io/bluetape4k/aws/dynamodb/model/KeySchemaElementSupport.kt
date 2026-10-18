package io.bluetape4k.aws.dynamodb.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.KeyType

inline fun keySchemaElement(
    builder: KeySchemaElement.Builder.() -> Unit,
): KeySchemaElement =
    KeySchemaElement.builder().apply(builder).build()

fun keySchemaElementOf(
    name: String,
    keyType: KeyType,
): KeySchemaElement = keySchemaElement {
    attributeName(name.requireNotBlank("name"))
    keyType(keyType)
}
