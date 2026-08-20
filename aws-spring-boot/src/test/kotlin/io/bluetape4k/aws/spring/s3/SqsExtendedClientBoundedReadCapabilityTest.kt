package io.bluetape4k.aws.spring.s3

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class SqsExtendedClientBoundedReadCapabilityTest {

    @Test
    fun `bounded capability constants leave room for max plus one probe`() {
        S3BoundedObjectReadOperations.MAX_BYTES shouldBeEqualTo 67_108_864
        S3BoundedEncryptedReadOperations.MAX_CIPHERTEXT_BYTES shouldBeEqualTo 67_108_880
        S3BoundedObjectReadOperations.MAX_BYTES.toLong().plus(1L) shouldBeEqualTo 67_108_865L
        S3BoundedEncryptedReadOperations.MAX_CIPHERTEXT_BYTES.toLong().plus(1L) shouldBeEqualTo 67_108_881L
    }

    @Test
    fun `metadata capability has conditional create result variants`() {
        S3PutIfAbsentResult.Created::class.java.isAssignableFrom(S3PutIfAbsentResult::class.java)
            .shouldBeEqualTo(false)
        S3PutIfAbsentResult::class.java.isAssignableFrom(S3PutIfAbsentResult.AlreadyExists::class.java)
            .shouldBeEqualTo(true)
        S3ObjectMetadataOperations::class.java.isAssignableFrom(S3CoroutinesTemplate::class.java)
            .shouldBeEqualTo(true)
    }
}
