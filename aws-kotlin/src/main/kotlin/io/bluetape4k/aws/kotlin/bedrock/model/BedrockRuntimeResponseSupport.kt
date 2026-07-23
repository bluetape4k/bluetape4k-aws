package io.bluetape4k.aws.kotlin.bedrock.model

import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseResponse
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamOutput

/**
 * Returns text content in native response order and skips non-text blocks.
 *
 * Empty or absent message content returns an empty list. The raw native SDK
 * response remains available to inspect skipped sealed-union variants.
 */
fun ConverseResponse.textContents(): List<String> =
    output?.asMessageOrNull()?.content.orEmpty()
        .mapNotNull(ContentBlock::asTextOrNull)

/**
 * Returns the first text block or `null` without traversing later blocks.
 *
 * Non-text sealed-union variants are skipped, and the raw native response
 * remains available.
 */
fun ConverseResponse.firstTextOrNull(): String? =
    output?.asMessageOrNull()?.content.orEmpty()
        .firstNotNullOfOrNull(ContentBlock::asTextOrNull)

/**
 * Joins text blocks in one traversal and skips non-text native content.
 *
 * Empty or absent content returns an empty string. The raw native response
 * remains available to inspect skipped variants.
 */
fun ConverseResponse.textOrEmpty(separator: String = ""): String =
    buildString {
        var first = true
        for (block in output?.asMessageOrNull()?.content.orEmpty()) {
            val text = block.asTextOrNull() ?: continue
            if (!first) append(separator)
            append(text)
            first = false
        }
    }

/**
 * Returns text from a native Bedrock content-block delta event.
 *
 * Non-text events return `null`; empty text remains an empty string. The raw
 * native SDK event remains available to inspect other sealed-union variants.
 */
fun ConverseStreamOutput.textDeltaOrNull(): String? =
    asContentBlockDeltaOrNull()?.delta?.asTextOrNull()
