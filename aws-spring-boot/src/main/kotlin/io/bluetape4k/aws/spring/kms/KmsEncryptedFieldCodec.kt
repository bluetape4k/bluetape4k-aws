package io.bluetape4k.aws.spring.kms

import java.lang.reflect.Field
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Explicit field-level String encryption codec backed by [KmsOperations].
 *
 * The codec returns versioned ciphertext strings so stored values can evolve
 * without guessing formats later. It intentionally does not perform transparent
 * reflection-based object mutation.
 */
class KmsEncryptedFieldCodec(
    private val kmsOperations: KmsOperations,
    private val keyId: String? = null,
    private val encryptionContext: Map<String, String> = emptyMap(),
) {

    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    suspend fun encrypt(value: String?, annotation: KmsEncrypted): String? {
        if (value == null) return null

        return try {
            val ciphertext = kmsOperations.encrypt(
                plaintext = value.toByteArray(StandardCharsets.UTF_8),
                keyId = annotation.effectiveKeyId(),
                encryptionContext = effectiveEncryptionContext(annotation),
            )

            CIPHERTEXT_PREFIX + encoder.encodeToString(ciphertext)
        } catch (e: KmsEncryptedFieldUsageException) {
            throw e
        } catch (e: KmsFieldEncryptionException) {
            throw e
        } catch (e: RuntimeException) {
            throw KmsFieldEncryptionException("Failed to encrypt KMS field value.", e)
        }
    }

    suspend fun decrypt(value: String?, annotation: KmsEncrypted): String? {
        if (value == null) return null
        if (!value.startsWith(CIPHERTEXT_PREFIX)) {
            throw MalformedKmsCiphertextException(
                "KMS field ciphertext must start with '$CIPHERTEXT_PREFIX'.",
            )
        }

        val encoded = value.removePrefix(CIPHERTEXT_PREFIX)
        val ciphertext = try {
            decoder.decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw MalformedKmsCiphertextException("KMS field ciphertext is not valid Base64.", e)
        }

        return try {
            kmsOperations.decrypt(
                ciphertext = ciphertext,
                keyId = annotation.effectiveKeyId(),
                encryptionContext = effectiveEncryptionContext(annotation),
            ).toString(StandardCharsets.UTF_8)
        } catch (e: KmsEncryptedFieldUsageException) {
            throw e
        } catch (e: KmsFieldEncryptionException) {
            throw e
        } catch (e: RuntimeException) {
            throw KmsFieldEncryptionException("Failed to decrypt KMS field ciphertext.", e)
        }
    }

    fun validate(type: Class<*>) {
        // Keep this explicit: the first slice validates only directly declared Java fields.
        type.declaredFields
            .filter { it.isAnnotationPresent(KmsEncrypted::class.java) }
            .forEach(::validate)
    }

    fun validate(field: Field) {
        if (!field.isAnnotationPresent(KmsEncrypted::class.java)) return
        if (field.type != String::class.java) {
            throw UnsupportedKmsEncryptedFieldException(
                "@KmsEncrypted supports only String fields: ${field.declaringClass.name}.${field.name}",
            )
        }
    }

    private fun KmsEncrypted.effectiveKeyId(): String? =
        keyId.takeIf { it.isNotBlank() } ?: this@KmsEncryptedFieldCodec.keyId

    private fun effectiveEncryptionContext(annotation: KmsEncrypted): Map<String, String> =
        encryptionContext + annotation.encryptionContext.toContextMap()

    private fun Array<String>.toContextMap(): Map<String, String> =
        associate { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0 || separator == entry.lastIndex) {
                throw KmsEncryptedFieldUsageException(
                    "@KmsEncrypted encryptionContext entry must use non-empty name=value form: '$entry'",
                )
            }

            entry.substring(0, separator) to entry.substring(separator + 1)
        }.also { context ->
            if (context.size != size) {
                throw KmsEncryptedFieldUsageException(
                    "@KmsEncrypted encryptionContext entries must not contain duplicate names.",
                )
            }
        }

    companion object {
        const val CIPHERTEXT_PREFIX: String = "b4k-kms:v1:"
    }
}

open class KmsFieldEncryptionException(
    message: String,
    cause: Throwable? = null,
): RuntimeException(message, cause)

open class KmsEncryptedFieldUsageException(
    message: String,
    cause: Throwable? = null,
): IllegalArgumentException(message, cause)

class MalformedKmsCiphertextException(
    message: String,
    cause: Throwable? = null,
): KmsEncryptedFieldUsageException(message, cause)

class UnsupportedKmsEncryptedFieldException(
    message: String,
): KmsEncryptedFieldUsageException(message)
