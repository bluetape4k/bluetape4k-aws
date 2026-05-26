package io.bluetape4k.aws.ktor.s3

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.net.URLConnection
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

private const val DEFAULT_BINARY_CONTENT_TYPE = "application/octet-stream"

/**
 * Server-side encryption settings for S3 PutObject and multipart-create requests.
 *
 * ## Behavior / Contract
 *
 * The value object renders only S3 request headers. It does not perform
 * client-side encryption and does not add an AWS KMS runtime dependency.
 */
sealed interface S3KtorServerSideEncryption: Serializable {

    /**
     * Returns S3 request headers for this encryption mode.
     */
    fun headers(): Map<String, String>

    /**
     * S3-managed server-side encryption (`AES256`).
     */
    data object S3Managed: S3KtorServerSideEncryption {
        private const val serialVersionUID: Long = 1L

        override fun headers(): Map<String, String> =
            mapOf("x-amz-server-side-encryption" to "AES256")
    }

    /**
     * AWS KMS server-side encryption.
     */
    data class Kms(
        val keyId: String? = null,
        val encryptionContext: Map<String, String> = emptyMap(),
        val bucketKeyEnabled: Boolean? = null,
        val dualLayer: Boolean = false,
    ): S3KtorServerSideEncryption {

        init {
            keyId?.requireNotBlank("keyId")
        }

        override fun headers(): Map<String, String> = buildMap {
            put("x-amz-server-side-encryption", if (dualLayer) "aws:kms:dsse" else "aws:kms")
            keyId?.let { put("x-amz-server-side-encryption-aws-kms-key-id", it) }
            if (encryptionContext.isNotEmpty()) {
                put("x-amz-server-side-encryption-context", encryptionContext.toBase64Json())
            }
            bucketKeyEnabled?.let { put("x-amz-server-side-encryption-bucket-key-enabled", it.toString()) }
        }

        private companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    /**
     * Customer-provided server-side encryption key (`SSE-C`).
     */
    class CustomerProvided(
        private val key: ByteArray,
        private val keyMd5: String? = null,
    ): S3KtorServerSideEncryption {

        init {
            require(key.isNotEmpty()) { "key must not be empty." }
            keyMd5?.requireNotBlank("keyMd5")
        }

        override fun headers(): Map<String, String> =
            mapOf(
                "x-amz-server-side-encryption-customer-algorithm" to "AES256",
                "x-amz-server-side-encryption-customer-key" to Base64.getEncoder().encodeToString(key),
                "x-amz-server-side-encryption-customer-key-MD5" to (keyMd5 ?: key.md5Base64()),
            )

        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }
}

/**
 * Content-type detector used by Ktor S3 upload helpers.
 */
fun interface S3KtorContentTypeDetector {

    /**
     * Detects a content type for [key] and optional [bytes].
     */
    fun detect(key: String, bytes: ByteArray?): String?
}

/**
 * Common S3 content-type detection helpers.
 */
object S3KtorContentTypes {

    /**
     * Detects by object key extension first, then by byte signature when bytes are supplied.
     */
    val Default: S3KtorContentTypeDetector = S3KtorContentTypeDetector { key, bytes ->
        URLConnection.guessContentTypeFromName(key)
            ?: bytes?.inputStream()?.use(URLConnection::guessContentTypeFromStream)
    }

    /**
     * Returns [detected] when non-blank, otherwise [fallback].
     */
    fun orFallback(detected: String?, fallback: String = DEFAULT_BINARY_CONTENT_TYPE): String =
        detected?.takeIf { it.isNotBlank() } ?: fallback
}

/**
 * Text object loaded from S3 for Ktor application configuration bootstrap.
 */
data class S3KtorConfigObject(
    val bucket: String,
    val key: String,
    val text: String,
    val charset: Charset = StandardCharsets.UTF_8,
    val contentType: String? = null,
    val metadata: Map<String, String> = emptyMap(),
): Serializable {

    init {
        bucket.requireNotBlank("bucket")
        key.requireNotBlank("key")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

private fun ByteArray.md5Base64(): String {
    val digest = MessageDigest.getInstance("MD5").digest(this)
    return Base64.getEncoder().encodeToString(digest)
}

private fun Map<String, String>.toBase64Json(): String =
    entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        "\"${key.escapeJson()}\":\"${value.escapeJson()}\""
    }.toByteArray(StandardCharsets.UTF_8)
        .let(Base64.getEncoder()::encodeToString)

private fun String.escapeJson(): String =
    buildString(length + 8) {
        this@escapeJson.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (ch < ' ') {
                        append("\\u")
                        append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        append(ch)
                    }
                }
            }
        }
    }
