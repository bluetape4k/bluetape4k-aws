package io.bluetape4k.aws.spring.s3

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

class SqsExtendedClientBoundedReadCapabilityTest {

    companion object: KLogging()

    @Test
    fun `bounded capability constants leave room for max plus one probe`() {
        S3BoundedObjectReadOperations.MAX_BYTES shouldBeEqualTo 67_108_864
        S3BoundedEncryptedReadOperations.MAX_CIPHERTEXT_BYTES shouldBeEqualTo 67_108_880
        S3BoundedObjectReadOperations.MAX_BYTES.toLong().plus(1L) shouldBeEqualTo 67_108_865L
        S3BoundedEncryptedReadOperations.MAX_CIPHERTEXT_BYTES.toLong().plus(1L) shouldBeEqualTo 67_108_881L
    }

    @Test
    fun `metadata capability has conditional create result variants`() {
        S3PutIfAbsentResult.Created::class.java
            .isAssignableFrom(S3PutIfAbsentResult::class.java).shouldBeFalse()
        S3PutIfAbsentResult::class.java

            .isAssignableFrom(S3PutIfAbsentResult.AlreadyExists::class.java).shouldBeTrue()

        S3ObjectMetadataOperations::class.java
            .isAssignableFrom(S3CoroutinesTemplate::class.java).shouldBeTrue()
    }
}
