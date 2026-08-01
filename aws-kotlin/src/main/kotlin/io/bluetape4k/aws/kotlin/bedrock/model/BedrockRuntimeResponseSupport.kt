package io.bluetape4k.aws.kotlin.bedrock.model

import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseResponse
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamOutput

/**
 * 네이티브 응답 순서대로 텍스트 내용을 반환하고 텍스트가 아닌 블록은 건너뜁니다.
 *
 * 메시지 내용이 비었거나 없으면 빈 목록을 반환합니다. 건너뛴 sealed union 변형을 확인할 수 있도록
 * 네이티브 SDK 원본 응답은 그대로 사용할 수 있습니다.
 */
fun ConverseResponse.textContents(): List<String> =
    output?.asMessageOrNull()?.content.orEmpty()
        .mapNotNull(ContentBlock::asTextOrNull)

/**
 * 뒤의 블록을 순회하지 않고 첫 번째 텍스트 블록 또는 `null`을 반환합니다.
 *
 * 텍스트가 아닌 sealed union 변형은 건너뛰며 네이티브 원본 응답은 그대로 사용할 수 있습니다.
 */
fun ConverseResponse.firstTextOrNull(): String? =
    output?.asMessageOrNull()?.content.orEmpty()
        .firstNotNullOfOrNull(ContentBlock::asTextOrNull)

/**
 * 한 번의 순회로 텍스트 블록을 합치고 텍스트가 아닌 네이티브 내용은 건너뜁니다.
 *
 * 내용이 비었거나 없으면 빈 문자열을 반환합니다. 건너뛴 변형을 확인할 수 있도록
 * 네이티브 원본 응답은 그대로 사용할 수 있습니다.
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
 * Bedrock 네이티브 콘텐츠 블록 델타 이벤트에서 텍스트를 반환합니다.
 *
 * 텍스트가 아닌 이벤트는 `null`을 반환하고 빈 텍스트는 빈 문자열로 유지합니다.
 * 다른 sealed union 변형을 확인할 수 있도록 네이티브 SDK 원본 이벤트는 그대로 사용할 수 있습니다.
 */
fun ConverseStreamOutput.textDeltaOrNull(): String? =
    asContentBlockDeltaOrNull()?.delta?.asTextOrNull()
