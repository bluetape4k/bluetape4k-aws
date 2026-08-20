package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.shouldContain
import org.junit.jupiter.api.Test

class SqsExtendedClientExampleTest {

    @Test
    fun `extended example configuration keeps the canonical opt in contract`() {
        val resource = requireNotNull(javaClass.getResourceAsStream("/application-extended.yml"))
            .bufferedReader()
            .use { it.readText() }

        resource shouldContain "extended:"
        resource shouldContain "producer-enabled: true"
        resource shouldContain "consumer-enabled: true"
        resource shouldContain "offload-threshold-bytes: 262144"
        resource shouldContain "max-offload-payload-bytes: 67108864"
        resource shouldContain "delete-on-ack: false"
    }
}
