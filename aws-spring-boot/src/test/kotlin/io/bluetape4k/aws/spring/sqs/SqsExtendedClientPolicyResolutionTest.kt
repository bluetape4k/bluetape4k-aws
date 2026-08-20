package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.codec.Base58
import org.junit.jupiter.api.Test

class SqsExtendedClientPolicyResolutionTest {

    @Test
    fun `queue specific policy wins over exact default allowlist`() {
        val defaultQueue = "https://sqs.us-east-1.amazonaws.com/123456789012/${Base58.randomString(16)}"
        val specificQueue = "https://sqs.us-east-1.amazonaws.com/123456789012/${Base58.randomString(16)}"
        val defaultPolicy = policy("default-${Base58.randomString(16)}")
        val specificPolicy = policy("specific-${Base58.randomString(16)}")
        val properties = SqsExtendedClientProperties(
            enabled = true,
            defaultPolicy = defaultPolicy,
            defaultQueueUrls = setOf(defaultQueue, specificQueue),
            queues = mapOf("specific" to SqsExtendedClientProperties.QueuePolicy(specificQueue, specificPolicy)),
        )

        properties.resolvePolicy(specificQueue) shouldBeEqualTo specificPolicy
        properties.resolvePolicy(defaultQueue) shouldBeEqualTo defaultPolicy
        properties.resolvePolicy("https://sqs.us-east-1.amazonaws.com/123456789012/foreign")
            .shouldBeNull()
    }

    @Test
    fun `policy without allowlist never applies to an unknown queue`() {
        val properties = SqsExtendedClientProperties(
            enabled = true,
            queues = emptyMap(),
        )

        properties.resolvePolicy("https://sqs.us-east-1.amazonaws.com/123456789012/unknown")
            .shouldBeNull()
    }

    private fun policy(bucket: String): SqsExtendedClientProperties.Policy =
        SqsExtendedClientProperties.Policy(
            bucket = bucket,
            configuredSqsRetentionSeconds = 14 * 3_600,
            configuredMaxVisibilityRetryWindowSeconds = 7 * 3_600,
            rollbackDeadlineSeconds = 24 * 3_600,
        )
}
