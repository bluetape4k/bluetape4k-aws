package io.bluetape4k.aws.spring.s3

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.jackson3.Jackson
import org.junit.jupiter.api.Test

class S3ObjectConverterTest {

    private val jsonMapper = Jackson.defaultJsonMapper

    @Test
    fun `Jackson converter supports typed object round trip`() {
        val converter = JacksonS3ObjectConverter(jsonMapper)
        val original = mapOf("name" to "bluetape", "revision" to 4)

        val restored = converter.read(converter.write(original), Map::class.java)

        restored shouldBeEqualTo original
        converter.contentType shouldBeEqualTo "application/json"
    }

    @Test
    fun `content type can be overridden by an object upload`() {
        val converter = JacksonS3ObjectConverter(jsonMapper, defaultContentType = "application/vnd.api+json")

        converter.contentType shouldBeEqualTo "application/vnd.api+json"
    }
}
