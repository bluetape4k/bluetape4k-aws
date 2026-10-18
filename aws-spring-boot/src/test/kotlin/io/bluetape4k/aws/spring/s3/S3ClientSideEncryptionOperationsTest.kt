package io.bluetape4k.aws.spring.s3

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.aws.spring.kms.KmsOperations
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.mockk.clearMocks
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.s3.S3AsyncClient

class S3ClientSideEncryptionOperationsTest: AbstractS3Test() {

    companion object: KLogging()

    private val asyncClient = mockk<S3AsyncClient>(relaxed = true)
    private val kmsOperations = mockk<KmsOperations>(relaxed = true)

    @BeforeEach
    fun beforeEach() {
        clearMocks(asyncClient, kmsOperations)
    }

    @Test
    fun `KMS key fingerprint is order independent and delimiter safe`() {
        val ordered = template(
            linkedMapOf(
                "언어" to "한;글=값",
                "empty" to "",
            ),
        )
        val reordered = template(
            linkedMapOf(
                "empty" to "",
                "언어" to "한;글=값",
            ),
        )

        log.debug { "ordered.keyFingerprint=${ordered.keyFingerprint}" }
        log.debug { "reordered.keyFingerprint=${reordered.keyFingerprint}" }
        
        ordered.keyFingerprint shouldBeEqualTo reordered.keyFingerprint

        val delimiterBearing = template(mapOf("a" to "b;c=d"))
        val splitEntries = template(
            linkedMapOf(
                "a" to "b",
                "c" to "d",
            ),
        )
        log.debug { "delimiterBearing=$delimiterBearing" }
        log.debug { "splitEntries=$splitEntries" }
        delimiterBearing.keyFingerprint shouldNotBeEqualTo splitEntries.keyFingerprint

        template(mapOf("a=b" to "c")).keyFingerprint shouldNotBeEqualTo
                template(mapOf("a" to "b=c")).keyFingerprint

        template(emptyMap()).keyFingerprint shouldNotBeEqualTo template(mapOf("empty" to "")).keyFingerprint
    }

    @Test
    fun `KMS encryption context rejects empty keys`() {
        val error = assertFailsWith<IllegalArgumentException> {
            S3Properties.ClientSideEncryption(encryptionContext = mapOf("" to "value"))
        }
        log.debug { "error=$error" }
        error.message shouldContain "encryptionContext"
    }

    @Test
    fun `KMS delimiter safe fingerprint preserves the v1 vector`() {
        val keyFingerprint = template(mapOf("purpose" to "orders")).keyFingerprint

        log.debug { "keyFingerprint=$keyFingerprint" }
        keyFingerprint shouldBeEqualTo "GlgsgfhByuQVvRFR7Xzew5jn-IbRgBmaVfR9KT4SodI"
    }

    private fun template(context: Map<String, String>): S3ClientSideEncryptionTemplate =
        S3ClientSideEncryptionTemplate(
            s3AsyncClient = asyncClient,
            kmsOperations = kmsOperations,
            properties = S3Properties(
                clientSideEncryption = S3Properties.ClientSideEncryption(
                    enabled = true,
                    keyId = "arn:aws:kms:us-east-1:123456789012:key/01234567-89ab-cdef-0123-456789abcdef",
                    encryptionContext = context,
                ),
            ),
        )
}
