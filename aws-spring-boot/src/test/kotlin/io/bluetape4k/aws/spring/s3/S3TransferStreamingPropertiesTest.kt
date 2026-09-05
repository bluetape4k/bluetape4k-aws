package io.bluetape4k.aws.spring.s3

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

@Suppress("DEPRECATION")
class S3TransferStreamingPropertiesTest {

    @Test
    fun `streaming thresholds must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            S3Properties.Transfer(outputStreamThresholdBytes = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            S3Properties.Transfer(outputStreamPartSizeBytes = -1)
        }
    }

    @Test
    fun `unsupported per-stream part size APIs are deprecated with supported alternatives`() {
        deprecatedMessage(
            S3Properties.Transfer::class.java,
            "getOutputStreamPartSizeBytes\$annotations",
        ) shouldContain "bluetape4k.aws.s3.crt.minimum-part-size-in-bytes"

        deprecatedMessage(
            S3OutputStream::class.java,
            "getPartSizeBytes\$annotations",
        ) shouldContain "S3AsyncClient multipart configuration"
    }

    @Test
    fun `Spring metadata deprecates the ignored output stream part size property`() {
        val metadata = requireNotNull(
            javaClass.getResourceAsStream("/META-INF/additional-spring-configuration-metadata.json"),
        ).use(ObjectMapper()::readTree)
        val property = metadata["properties"].first {
            it["name"].asText() == "bluetape4k.aws.s3.transfer.output-stream-part-size-bytes"
        }

        property["deprecation"]["level"].asText() shouldBeEqualTo "warning"
        property["deprecation"]["replacement"].asText() shouldBeEqualTo
            "bluetape4k.aws.s3.crt.minimum-part-size-in-bytes"
        metadata["properties"].any {
            it["name"].asText() == "bluetape4k.aws.s3.crt.minimum-part-size-in-bytes"
        } shouldBeEqualTo true
    }

    private fun deprecatedMessage(type: Class<*>, annotationMethod: String): String =
        requireNotNull(
            type.getDeclaredMethod(annotationMethod).getAnnotation(Deprecated::class.java),
        ).message
}
