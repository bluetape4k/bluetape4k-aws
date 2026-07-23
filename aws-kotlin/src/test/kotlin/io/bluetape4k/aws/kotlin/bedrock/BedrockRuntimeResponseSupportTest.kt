package io.bluetape4k.aws.kotlin.bedrock

import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlockDelta
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlockDeltaEvent
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseOutput
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseResponse
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamOutput
import aws.sdk.kotlin.services.bedrockruntime.model.Message
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.aws.kotlin.bedrock.model.firstTextOrNull
import io.bluetape4k.aws.kotlin.bedrock.model.textContents
import io.bluetape4k.aws.kotlin.bedrock.model.textDeltaOrNull
import io.bluetape4k.aws.kotlin.bedrock.model.textOrEmpty
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class BedrockRuntimeResponseSupportTest {

    @Test
    fun `text helpers skip non-text blocks and preserve order`() {
        val response = responseOf(
            ContentBlock.Text("hello"),
            ContentBlock.SdkUnknown,
            ContentBlock.Text(" world"),
        )

        response.textContents() shouldBeEqualTo listOf("hello", " world")
        response.firstTextOrNull() shouldBeEqualTo "hello"
        response.textOrEmpty() shouldBeEqualTo "hello world"
        response.textOrEmpty("|") shouldBeEqualTo "hello| world"
    }

    @Test
    fun `missing text maps to empty values`() {
        val response = mockk<ConverseResponse>()
        every { response.output } returns null
        response.textContents().shouldBeEmpty()
        response.firstTextOrNull().shouldBeNull()
        response.textOrEmpty().shouldBeEmpty()
        responseOf().textContents().shouldBeEmpty()
    }

    @Test
    fun `large text response preserves order`() {
        val values = List(1_000) { "value-$it" }
        val response = responseOf(*values.map(ContentBlock::Text).toTypedArray())

        response.textContents() shouldBeEqualTo values
        response.textOrEmpty("|") shouldBeEqualTo values.joinToString("|")
    }

    @Test
    fun `stream output exposes only text deltas and preserves empty text`() {
        fun delta(text: String) = ConverseStreamOutput.ContentBlockDelta(
            ContentBlockDeltaEvent {
                contentBlockIndex = 0
                delta = ContentBlockDelta.Text(text)
            },
        )

        delta("delta").textDeltaOrNull() shouldBeEqualTo "delta"
        delta("").textDeltaOrNull() shouldBeEqualTo ""
        ConverseStreamOutput.SdkUnknown.textDeltaOrNull().shouldBeNull()
    }

    @Test
    fun `first text stops early and join traverses content once`() {
        val firstList = CountingContentList(
            listOf(
                ContentBlock.SdkUnknown,
                ContentBlock.Text("first"),
                ContentBlock.Text("later"),
            ),
        )
        responseWith(firstList).firstTextOrNull() shouldBeEqualTo "first"
        firstList.getCount shouldBeEqualTo 2

        val joinList = CountingContentList(
            List(1_000) { ContentBlock.Text("value-$it") },
        )
        responseWith(joinList).textOrEmpty("|") shouldBeEqualTo List(1_000) { "value-$it" }.joinToString("|")
        joinList.iteratorCount shouldBeEqualTo 1
        joinList.getCount shouldBeEqualTo 1_000
    }

    @Test
    fun `single-pass helpers do not delegate to textContents`() {
        val candidates = listOf(
            Path.of(
                "src/main/kotlin/io/bluetape4k/aws/kotlin/bedrock/model/BedrockRuntimeResponseSupport.kt",
            ),
            Path.of(
                "aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/bedrock/model/BedrockRuntimeResponseSupport.kt",
            ),
        )
        val source = Files.readString(candidates.first(Files::exists))
        val firstBody = source.substringAfter("fun ConverseResponse.firstTextOrNull()")
            .substringBefore("/**")
        val joinBody = source.substringAfter("fun ConverseResponse.textOrEmpty")
            .substringBefore("/**")

        require(!firstBody.contains("textContents("))
        require(!joinBody.contains("textContents("))
    }

    private fun responseOf(vararg blocks: ContentBlock): ConverseResponse =
        responseWith(blocks.toList())

    private fun responseWith(blocks: List<ContentBlock>): ConverseResponse {
        val response = mockk<ConverseResponse>()
        val message = mockk<Message>()
        every { response.output } returns ConverseOutput.Message(message)
        every { message.content } returns blocks
        return response
    }

    private class CountingContentList(
        private val delegate: List<ContentBlock>,
    ) : AbstractList<ContentBlock>() {
        var getCount = 0
            private set
        var iteratorCount = 0
            private set

        override val size: Int
            get() = delegate.size

        override fun get(index: Int): ContentBlock {
            getCount++
            return delegate[index]
        }

        override fun iterator(): Iterator<ContentBlock> {
            iteratorCount++
            return super.iterator()
        }
    }
}
