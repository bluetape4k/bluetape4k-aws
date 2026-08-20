package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.codec.Base58
import org.junit.jupiter.api.Test

class SqsExtendedClientPropertiesRedactionTest {

    @Test
    fun `nested properties and policy display omit queue bucket prefix and context`() {
        val bucket = "bucket-${Base58.randomString(16)}"
        val prefix = "prefix-${Base58.randomString(16)}"
        val queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789012/${Base58.randomString(16)}"
        val contextValue = "context-${Base58.randomString(16)}"
        val fingerprint = "fingerprint-${Base58.randomString(16)}"
        val policy = SqsExtendedClientProperties.Policy(
            bucket = bucket,
            keyPrefix = prefix,
            configuredSqsRetentionSeconds = 14 * 3_600,
            configuredMaxVisibilityRetryWindowSeconds = 7 * 3_600,
            rollbackDeadlineSeconds = 24 * 3_600,
            encryption = SqsExtendedClientProperties.Encryption(
                enabled = true,
                encryptionContext = mapOf("tenant" to contextValue),
                keyFingerprint = fingerprint,
            ),
        )
        val properties = SqsExtendedClientProperties(
            enabled = true,
            producerEnabled = true,
            consumerEnabled = true,
            defaultPolicy = policy,
            defaultQueueUrls = setOf(queueUrl),
        )

        listOf(properties.toString(), policy.toString(), policy.encryption.toString()).forEach { rendered ->
            rendered shouldNotContain bucket
            rendered shouldNotContain prefix
            rendered shouldNotContain queueUrl
            rendered shouldNotContain contextValue
            rendered shouldNotContain fingerprint
        }
    }
}
