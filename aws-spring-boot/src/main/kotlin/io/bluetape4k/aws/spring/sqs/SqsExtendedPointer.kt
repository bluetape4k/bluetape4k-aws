@file:Suppress("MagicNumber")

package io.bluetape4k.aws.spring.sqs

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val POINTER_BASE64_URL_REGEX = Regex("[A-Za-z0-9_-]+")

class SqsExtendedClientPointer private constructor(
    val bucket: String,
    val key: String,
    val contentType: String?,
    val encrypted: Boolean,
    val signatureBase64Url: String,
) {
    override fun toString(): String = "SqsExtendedClientPointer(encrypted=$encrypted, present=true)"

    override fun equals(other: Any?): Boolean =
        other is SqsExtendedClientPointer &&
            bucket == other.bucket && key == other.key && contentType == other.contentType &&
            encrypted == other.encrypted && signatureBase64Url == other.signatureBase64Url

    override fun hashCode(): Int = listOf(bucket, key, contentType, encrypted, signatureBase64Url).hashCode()

    internal companion object {
        fun create(
            bucket: String,
            key: String,
            contentType: String?,
            encrypted: Boolean,
            signatureBase64Url: String,
        ): SqsExtendedClientPointer {
            require(bucket.isNotBlank() && bucket.none(::isControl)) {
                "pointer bucket must be non-blank and control-free."
            }
            require(key.isNotBlank() && key.none(::isControl)) {
                "pointer key must be non-blank and control-free."
            }
            require(contentType == null || contentType.none(::isControl)) {
                "pointer contentType must be control-free."
            }
            require(signatureBase64Url.matches(POINTER_BASE64_URL_REGEX)) {
                "pointer signature must be canonical base64url."
            }
            return SqsExtendedClientPointer(bucket, key, contentType, encrypted, signatureBase64Url)
        }

        private fun isControl(value: Char): Boolean = value == '\u0000' || value == '\r' || value == '\n'
    }
}

internal object SqsExtendedPointerCodec {
    private const val PREFIX = "bt4k-sqs-extended/v1"
    private const val TYPE = "sqs-pointer"

    fun encode(
        bucket: String,
        key: String,
        contentType: String?,
        encrypted: Boolean,
        queueUrl: String,
        policyFingerprint: String,
        signingKey: ByteArray,
    ): String {
        require(signingKey.size >= 32) { "pointer signing key must be at least 32 bytes." }
        val payload = listOf(
            PREFIX,
            TYPE,
            queueUrl,
            policyFingerprint,
            bucket,
            key,
            contentType.orEmpty(),
            if (encrypted) "1" else "0",
        ).joinToString("\u0000")
        val payloadEncoded = base64Url(payload.toByteArray(StandardCharsets.UTF_8))
        val signature = base64Url(hmac(payload.toByteArray(StandardCharsets.UTF_8), signingKey))
        return "$PREFIX.$payloadEncoded.$signature"
    }

    fun decode(
        encoded: String,
        expectedQueueUrl: String,
        expectedPolicyFingerprint: String,
        signingKey: ByteArray,
    ): SqsExtendedClientPointer {
        try {
            require(signingKey.size >= 32)
            val parts = encoded.split('.')
            require(parts.size == 3 && parts[0] == PREFIX)
            val payload = Base64.getUrlDecoder().decode(parts[1])
            val signature = Base64.getUrlDecoder().decode(parts[2])
            require(base64Url(payload) == parts[1])
            require(base64Url(signature) == parts[2])
            require(signature.size == 32)
            require(MessageDigest.isEqual(signature, hmac(payload, signingKey)))
            val fields = payload.toString(StandardCharsets.UTF_8).split('\u0000')
            require(fields.size == 8 && fields[0] == PREFIX && fields[1] == TYPE)
            require(fields[2] == expectedQueueUrl && fields[3] == expectedPolicyFingerprint)
            val encrypted = when (fields[7]) {
                "0" -> false
                "1" -> true
                else -> error("invalid pointer encryption flag")
            }
            return SqsExtendedClientPointer.create(
                bucket = fields[4],
                key = fields[5],
                contentType = fields[6].ifEmpty { null },
                encrypted = encrypted,
                signatureBase64Url = parts[2],
            )
        } catch (_: Throwable) {
            throw SqsExtendedPointerFormatException.create()
        }
    }

    private fun hmac(payload: ByteArray, key: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(key, "HmacSHA256"))
        }.doFinal(payload)

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
