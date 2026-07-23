package io.bluetape4k.aws.bedrock

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.aws.bedrock.model.firstTextOrNull
import io.bluetape4k.aws.bedrock.model.textContents
import io.bluetape4k.aws.bedrock.model.textDeltaOrNull
import io.bluetape4k.aws.bedrock.model.textOrEmpty
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDelta
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDeltaEvent
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse
import software.amazon.awssdk.services.bedrockruntime.model.Message
import software.amazon.awssdk.services.bedrockruntime.model.MessageStopEvent
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock
import java.nio.file.Files
import java.nio.file.Path

class BedrockRuntimeResponseSupportTest {

    @Test
    fun `text helpers skip non-text blocks and preserve order`() {
        val response = responseOf(
            ContentBlock.builder().text("hello").build(),
            ContentBlock.builder()
                .toolUse(
                    ToolUseBlock.builder()
                        .toolUseId("tool-id")
                        .name("search")
                        .build(),
                )
                .build(),
            ContentBlock.builder().text(" world").build(),
        )

        response.textContents() shouldBeEqualTo listOf("hello", " world")
        response.firstTextOrNull() shouldBeEqualTo "hello"
        response.textOrEmpty() shouldBeEqualTo "hello world"
        response.textOrEmpty("|") shouldBeEqualTo "hello| world"
    }

    @Test
    fun `missing text maps to empty values`() {
        ConverseResponse.builder().build().textContents().shouldBeEmpty()
        ConverseResponse.builder().build().firstTextOrNull().shouldBeNull()
        ConverseResponse.builder().build().textOrEmpty().shouldBeEmpty()
        ConverseResponse.builder()
            .output(ConverseOutput.builder().build())
            .build()
            .textContents()
            .shouldBeEmpty()
        responseOf().textContents().shouldBeEmpty()
    }

    @Test
    fun `large text response joins without losing content`() {
        val values = List(1_000) { "value-$it" }
        val response = responseOf(*values.map { ContentBlock.builder().text(it).build() }.toTypedArray())

        response.textContents() shouldBeEqualTo values
        response.textOrEmpty("|") shouldBeEqualTo values.joinToString("|")
    }

    @Test
    fun `stream output exposes only text deltas`() {
        val textDelta = ContentBlockDeltaEvent.builder()
            .delta(ContentBlockDelta.builder().text("delta").build())
            .build()

        textDelta.textDeltaOrNull() shouldBeEqualTo "delta"
        MessageStopEvent.builder().build().textDeltaOrNull().shouldBeNull()
    }

    @Test
    fun `first text stops early and join traverses content once`() {
        val firstList = CountingContentList(
            listOf(
                ContentBlock.builder()
                    .toolUse(ToolUseBlock.builder().toolUseId("id").name("tool").build())
                    .build(),
                ContentBlock.builder().text("first").build(),
                ContentBlock.builder().text("later").build(),
            ),
        )
        responseWith(firstList).firstTextOrNull() shouldBeEqualTo "first"
        firstList.getCount shouldBeEqualTo 2

        val joinList = CountingContentList(
            List(1_000) { ContentBlock.builder().text("value-$it").build() },
        )
        responseWith(joinList).textOrEmpty("|") shouldBeEqualTo List(1_000) { "value-$it" }.joinToString("|")
        joinList.iteratorCount shouldBeEqualTo 1
        joinList.getCount shouldBeEqualTo 1_000
    }

    @Test
    fun `single-pass helpers do not delegate to textContents`() {
        val candidates = listOf(
            Path.of(
                "src/main/kotlin/io/bluetape4k/aws/bedrock/model/BedrockRuntimeResponseSupport.kt",
            ),
            Path.of(
                "aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/model/BedrockRuntimeResponseSupport.kt",
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
        ConverseResponse.builder()
            .output(
                ConverseOutput.builder()
                    .message(
                        Message.builder()
                            .role(ConversationRole.ASSISTANT)
                            .content(*blocks)
                            .build(),
                    )
                    .build(),
            )
            .build()

    private fun responseWith(blocks: List<ContentBlock>): ConverseResponse {
        val response = mockk<ConverseResponse>()
        val output = mockk<ConverseOutput>()
        val message = mockk<Message>()
        every { response.output() } returns output
        every { output.message() } returns message
        every { message.content() } returns blocks
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
