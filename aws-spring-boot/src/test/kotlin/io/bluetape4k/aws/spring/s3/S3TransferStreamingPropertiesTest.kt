package io.bluetape4k.aws.spring.s3

import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

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
}
