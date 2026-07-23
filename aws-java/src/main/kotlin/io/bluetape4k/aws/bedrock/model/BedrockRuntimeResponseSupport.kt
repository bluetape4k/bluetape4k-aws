package io.bluetape4k.aws.bedrock.model

import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDeltaEvent
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamOutput

/**
 * Returns text content in native response order and skips non-text blocks.
 *
 * The raw native SDK response remains available to inspect skipped content.
 */
fun ConverseResponse.textContents(): List<String> =
    output()?.message()?.content().orEmpty()
        .mapNotNull(ContentBlock::text)

/**
 * Returns the first text block or `null` without traversing later blocks.
 *
 * Non-text blocks are skipped, and the raw native SDK response remains
 * available to inspect them.
 */
fun ConverseResponse.firstTextOrNull(): String? =
    output()?.message()?.content().orEmpty()
        .firstNotNullOfOrNull(ContentBlock::text)

/**
 * Joins text blocks in one traversal and skips non-text native content.
 *
 * The raw native SDK response remains available to inspect skipped content.
 */
fun ConverseResponse.textOrEmpty(separator: String = ""): String =
    buildString {
        var first = true
        for (block in output()?.message()?.content().orEmpty()) {
            val text = block.text() ?: continue
            if (!first) append(separator)
            append(text)
            first = false
        }
    }

/**
 * Returns text from a native Bedrock content-block delta event.
 *
 * Non-text stream events return `null`; the raw native SDK event remains
 * available to inspect other variants.
 */
fun ConverseStreamOutput.textDeltaOrNull(): String? =
    (this as? ContentBlockDeltaEvent)?.delta()?.text()
