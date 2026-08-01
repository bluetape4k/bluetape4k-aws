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
 * S3 PutObject 및 멀티파트 생성 요청의 서버 측 암호화 설정입니다.
 *
 * ## 동작/계약
 *
 * 이 값 객체는 S3 요청 헤더만 생성합니다. 클라이언트 측 암호화를 수행하지 않으며
 * AWS KMS 런타임 의존성을 추가하지 않습니다.
 */
sealed interface S3KtorServerSideEncryption: Serializable {

    /**
     * 이 암호화 모드의 S3 요청 헤더를 반환합니다.
     */
    fun headers(): Map<String, String>

    /**
     * S3 관리형 서버 측 암호화(`AES256`)입니다.
     */
    data object S3Managed: S3KtorServerSideEncryption {
        private const val serialVersionUID: Long = 1L

        override fun headers(): Map<String, String> =
            mapOf("x-amz-server-side-encryption" to "AES256")
    }

    /**
     * AWS KMS 서버 측 암호화입니다.
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
     * 고객 제공 서버 측 암호화 키(`SSE-C`)입니다.
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
 * Ktor S3 업로드 도우미에서 사용하는 콘텐츠 타입 감지기입니다.
 */
fun interface S3KtorContentTypeDetector {

    /**
     * [key]와 선택적인 [bytes]에서 콘텐츠 타입을 감지합니다.
     */
    fun detect(key: String, bytes: ByteArray?): String?
}

/**
 * 공통 S3 콘텐츠 타입 감지 도우미입니다.
 */
object S3KtorContentTypes {

    /**
     * 먼저 객체 키 확장자로 감지하고, 바이트가 제공되면 바이트 시그니처를 확인합니다.
     */
    val Default: S3KtorContentTypeDetector = S3KtorContentTypeDetector { key, bytes ->
        URLConnection.guessContentTypeFromName(key)
            ?: bytes?.inputStream()?.use(URLConnection::guessContentTypeFromStream)
    }

    /**
     * [detected]가 비어 있지 않으면 이를 반환하고, 그렇지 않으면 [fallback]을 반환합니다.
     */
    fun orFallback(detected: String?, fallback: String = DEFAULT_BINARY_CONTENT_TYPE): String =
        detected?.takeIf { it.isNotBlank() } ?: fallback
}

/**
 * Ktor 애플리케이션 구성 부트스트랩을 위해 S3에서 읽은 텍스트 객체입니다.
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
