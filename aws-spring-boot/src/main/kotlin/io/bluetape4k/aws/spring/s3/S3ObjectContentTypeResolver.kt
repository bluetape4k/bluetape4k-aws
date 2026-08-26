package io.bluetape4k.aws.spring.s3

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * S3 객체의 Content-Type을 결정합니다.
 *
 * 명시적 override, 객체 metadata, key의 파일명 추론, 안전한 binary 기본값 순서로
 * 결정합니다. metadata 키는 S3와 HTTP 양쪽의 대소문자 표기를 허용합니다.
 */
interface S3ObjectContentTypeResolver {
    fun resolve(
        key: String,
        overrideContentType: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ): String
}

/** 파일명과 metadata를 이용하는 기본 Content-Type resolver입니다. */
class DefaultS3ObjectContentTypeResolver(
    private val contentTypeProbe: (Path) -> String? = { Files.probeContentType(it) },
) : S3ObjectContentTypeResolver {

    override fun resolve(
        key: String,
        overrideContentType: String?,
        metadata: Map<String, String>,
    ): String {
        return overrideContentType.normalizeContentType()
            ?: metadata.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }
                ?.value.normalizeContentType()
            ?: contentTypeProbe(Paths.get(key)).normalizeContentType()
            ?: DEFAULT_CONTENT_TYPE
    }

    private fun String?.normalizeContentType(): String? =
        this?.trim()?.takeIf(String::isNotEmpty)

    companion object {
        const val DEFAULT_CONTENT_TYPE: String = "application/octet-stream"
    }
}
