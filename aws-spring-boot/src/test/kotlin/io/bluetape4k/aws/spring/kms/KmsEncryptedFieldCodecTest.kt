package io.bluetape4k.aws.spring.kms

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import software.amazon.awssdk.services.kms.model.DataKeySpec

class KmsEncryptedFieldCodecTest {

    private val operations = RecordingKmsOperations()
    private val codec = KmsEncryptedFieldCodec(
        kmsOperations = operations,
        keyId = "alias/default",
        encryptionContext = mapOf("service" to "orders", "field" to "default"),
    )

    @Test
    fun `encrypt and decrypt annotated String field`() = runSuspendIO {
        val encrypted = codec.encrypt("short secret", secretAnnotation)

        encrypted shouldNotBeEqualTo "short secret"
        encrypted!!.startsWith(KmsEncryptedFieldCodec.CIPHERTEXT_PREFIX) shouldBeEqualTo true
        operations.lastEncryptKeyId shouldBeEqualTo "alias/customer-secret"
        operations.lastEncryptContext shouldBeEqualTo mapOf(
            "service" to "orders",
            "field" to "customerSecret",
            "purpose" to "test",
        )

        codec.decrypt(encrypted, secretAnnotation) shouldBeEqualTo "short secret"
        operations.lastDecryptKeyId shouldBeEqualTo "alias/customer-secret"
        operations.lastDecryptContext shouldBeEqualTo mapOf(
            "service" to "orders",
            "field" to "customerSecret",
            "purpose" to "test",
        )
    }

    @Test
    fun `nullable String values pass through as null`() = runSuspendIO {
        codec.encrypt(null, secretAnnotation).shouldBeNull()
        codec.decrypt(null, secretAnnotation).shouldBeNull()
    }

    @Test
    fun `configured key id is used when annotation omits key id`() = runSuspendIO {
        codec.encrypt("value", defaultKeyAnnotation)

        operations.lastEncryptKeyId shouldBeEqualTo "alias/default"
        operations.lastEncryptContext shouldBeEqualTo mapOf(
            "service" to "orders",
            "field" to "defaultKey",
        )
    }

    @Test
    fun `malformed ciphertext fails before KMS decrypt`() = runSuspendIO {
        assertThrows<MalformedKmsCiphertextException> {
            runSuspendIO { codec.decrypt("plain text", secretAnnotation) }
        }

        assertThrows<MalformedKmsCiphertextException> {
            runSuspendIO {
                codec.decrypt(KmsEncryptedFieldCodec.CIPHERTEXT_PREFIX + "not base64", secretAnnotation)
            }
        }
    }

    @Test
    fun `unsupported annotated field type fails fast`() {
        val exception = assertThrows<UnsupportedKmsEncryptedFieldException> {
            codec.validate(UnsupportedFixture::class.java)
        }

        exception.message shouldBeEqualTo
            "@KmsEncrypted supports only String fields: ${UnsupportedFixture::class.java.name}.secret"
    }

    @Test
    fun `invalid encryption context entry fails fast`() = runSuspendIO {
        val exception = assertThrows<KmsEncryptedFieldUsageException> {
            runSuspendIO { codec.encrypt("value", invalidContextAnnotation) }
        }

        exception.message shouldBeEqualTo
            "@KmsEncrypted encryptionContext entry must use non-empty name=value form: 'broken'"
    }

    @Test
    fun `duplicate encryption context names fail fast`() = runSuspendIO {
        val exception = assertThrows<KmsEncryptedFieldUsageException> {
            runSuspendIO { codec.encrypt("value", duplicateContextAnnotation) }
        }

        exception.message shouldBeEqualTo
            "@KmsEncrypted encryptionContext entries must not contain duplicate names."
    }

    private data class SecretFixture(
        @field:KmsEncrypted(
            keyId = "alias/customer-secret",
            encryptionContext = ["field=customerSecret", "purpose=test"],
        )
        val secret: String,

        @field:KmsEncrypted(encryptionContext = ["field=defaultKey"])
        val defaultKey: String,

        @field:KmsEncrypted(encryptionContext = ["broken"])
        val invalidContext: String,

        @field:KmsEncrypted(encryptionContext = ["field=first", "field=second"])
        val duplicateContext: String,
    )

    private data class UnsupportedFixture(
        @field:KmsEncrypted
        val secret: Int,
    )

    private companion object {
        val secretAnnotation: KmsEncrypted =
            SecretFixture::class.java.getDeclaredField("secret").getAnnotation(KmsEncrypted::class.java)

        val defaultKeyAnnotation: KmsEncrypted =
            SecretFixture::class.java.getDeclaredField("defaultKey").getAnnotation(KmsEncrypted::class.java)

        val invalidContextAnnotation: KmsEncrypted =
            SecretFixture::class.java.getDeclaredField("invalidContext").getAnnotation(KmsEncrypted::class.java)

        val duplicateContextAnnotation: KmsEncrypted =
            SecretFixture::class.java.getDeclaredField("duplicateContext").getAnnotation(KmsEncrypted::class.java)
    }

    private class RecordingKmsOperations: KmsOperations {
        var lastEncryptKeyId: String? = null
        var lastEncryptContext: Map<String, String> = emptyMap()
        var lastDecryptKeyId: String? = null
        var lastDecryptContext: Map<String, String> = emptyMap()

        override suspend fun encrypt(
            plaintext: ByteArray,
            keyId: String?,
            encryptionContext: Map<String, String>,
        ): ByteArray {
            lastEncryptKeyId = keyId
            lastEncryptContext = encryptionContext
            return ("cipher:" + plaintext.decodeToString()).encodeToByteArray()
        }

        override suspend fun decrypt(
            ciphertext: ByteArray,
            keyId: String?,
            encryptionContext: Map<String, String>,
        ): ByteArray {
            lastDecryptKeyId = keyId
            lastDecryptContext = encryptionContext
            return ciphertext.decodeToString().removePrefix("cipher:").encodeToByteArray()
        }

        override suspend fun generateDataKey(
            keyId: String?,
            keySpec: DataKeySpec?,
            numberOfBytes: Int?,
            encryptionContext: Map<String, String>,
            useCache: Boolean,
        ): KmsDataKey =
            KmsDataKey(
                keyId = keyId ?: "noop-key",
                plaintext = byteArrayOf(1, 2, 3),
                encryptedDataKey = byteArrayOf(4, 5, 6),
            )
    }
}
