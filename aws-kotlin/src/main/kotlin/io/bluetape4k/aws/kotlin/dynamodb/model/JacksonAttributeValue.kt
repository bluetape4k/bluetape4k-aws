package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeType

/**
 * Converts a Jackson [JsonNode] into a DynamoDB [AttributeValue].
 *
 * ## Behavior and contract
 * - NULL nodes convert to `AttributeValue.Null(true)`.
 * - BOOLEAN, NUMBER, and STRING nodes convert to their matching [AttributeValue] scalar types.
 * - ARRAY nodes convert to `AttributeValue.L`; OBJECT and POJO nodes convert to `AttributeValue.M`.
 * - Unsupported node types throw `IllegalStateException`.
 *
 * ```kotlin
 * val node: JsonNode = ObjectMapper().readTree("""{"name":"Alice","age":30}""")
 * val av = node.toAttributeValue()
 * // av is AttributeValue.M
 * // (av as AttributeValue.M).value["name"] == AttributeValue.S("Alice")
 * ```
 *
 * @throws IllegalStateException when the [JsonNodeType] is unsupported.
 */
fun JsonNode.toAttributeValue(): AttributeValue =
    when (this.nodeType) {
        JsonNodeType.NULL -> AttributeValue.Null(true)
        JsonNodeType.BOOLEAN -> this.booleanValue().toAttributeValue()
        JsonNodeType.NUMBER -> this.numberValue().toAttributeValue()
        JsonNodeType.STRING -> this.stringValue().toAttributeValue()
        JsonNodeType.ARRAY -> AttributeValue.L(
            this.values().map { it.toAttributeValue() }
        )
        JsonNodeType.OBJECT -> AttributeValue.M(
            this.properties().associate { (key, value) ->
                key to value.toAttributeValue()
            }
        )
        JsonNodeType.POJO -> AttributeValue.M(
            this.properties().associate { (key, value) ->
                key to value.toAttributeValue()
            }
        )

        else -> throw IllegalStateException("Unsupported JsonNode type: ${this.nodeType}")
    }
