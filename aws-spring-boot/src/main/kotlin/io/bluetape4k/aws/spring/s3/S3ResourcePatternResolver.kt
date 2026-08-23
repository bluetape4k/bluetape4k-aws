package io.bluetape4k.aws.spring.s3

import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationContext
import org.springframework.core.io.Resource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.io.support.ResourcePatternResolver
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import java.io.IOException

/**
 * S3 exact 위치와 제한된 S3 객체 패턴을 Spring [ResourcePatternResolver]로
 * 노출한다.
 *
 * S3 URI는 하나의 literal bucket과 비어 있지 않은 prefix만 사용할 수 있고,
 * 패턴에는 `*`, `?`, `**`만 허용된다. S3가 아닌 위치는 Spring의 기본
 * [PathMatchingResourcePatternResolver]에 위임한다. 이 resolver는 동기적으로
 * 모든 S3 paginator 페이지를 소비하며, client·context·stream의 수명을 소유하지
 * 않는다.
 */
open class S3ResourcePatternResolver(
    private val applicationContext: ApplicationContext,
    private val s3ClientProvider: ObjectProvider<S3Client>,
): ResourcePatternResolver {

    private val delegate = PathMatchingResourcePatternResolver(applicationContext)
    private val parser = S3ResourceLocationParser()

    /**
     * exact `s3://bucket/key`는 지연 조회한 client로 [S3Resource]를 만든다.
     * 그 밖의 위치는 기본 Spring resolver에 위임한다.
     */
    override fun getResource(location: String): Resource {
        if (!location.startsWith("s3:", ignoreCase = true)) {
            return delegate.getResource(location)
        }
        return S3Resource(s3ClientProvider.getObject(), parser.parseExact(location))
    }

    /**
     * exact S3 위치는 단일 resource를 반환하고, wildcard 패턴은 literal bucket의
     * non-empty prefix를 한 번 listing한다. 모든 페이지를 caller thread에서
     * 소비하므로 listing·전송 오류는 bucket과 prefix 진단을 포함한 [IOException]으로
     * 전파된다. 반환 resource의 stream과 주입 client의 수명은 caller/context가
     * 관리한다.
     */
    @Throws(IOException::class)
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    override fun getResources(locationPattern: String): Array<Resource> {
        if (!locationPattern.startsWith("s3:", ignoreCase = true)) {
            return delegate.getResources(locationPattern)
        }

        val pattern = parser.parsePattern(locationPattern)
        if (!pattern.hasWildcards) {
            val location = S3ObjectLocation(pattern.bucket, pattern.prefix)
            return arrayOf(S3Resource(s3ClientProvider.getObject(), location))
        }

        return try {
            val preparedPattern = pattern.prepareMatcher()
            val request = ListObjectsV2Request.builder()
                .bucket(pattern.bucket)
                .prefix(pattern.prefix)
                .build()
            val s3Client = s3ClientProvider.getObject()
            val matchedKeys = HashSet<String>()
            for (page in s3Client.listObjectsV2Paginator(request)) {
                for (s3Object in page.contents()) {
                    val key = s3Object.key() ?: continue
                    if (preparedPattern.matches(key)) {
                        matchedKeys += key
                    }
                }
            }

            val sortedKeys = matchedKeys.toMutableList().apply {
                sortWith(Comparator { left, right -> left.compareTo(right) })
            }
            Array<Resource>(sortedKeys.size) { index ->
                S3Resource(s3Client, S3ObjectLocation(pattern.bucket, sortedKeys[index]))
            }
        } catch (cause: Exception) {
            throw IOException(
                "S3 resource listing failed for bucket ${safeS3DiagnosticPart(pattern.bucket)} " +
                    "and prefix ${safeS3DiagnosticPart(pattern.prefix)}.",
                cause,
            )
        }
    }

    /**
     * classpath/file 등 non-S3 resource를 해석하는 기본 resolver의 class loader를
     * 그대로 반환한다.
     */
    override fun getClassLoader(): ClassLoader? = delegate.classLoader
}

/**
 * 외부 입력을 예외 진단에 제한적으로 표시한다. 제어 문자는 escape하고 UTF-16
 * 기준 128자로 제한해 URI·credential·header·원인 메시지가 진단에 섞이지 않게 한다.
 */
internal fun safeS3DiagnosticPart(value: String): String {
    val escaped = StringBuilder()
    var truncated = false
    value.codePoints().forEach { codePoint ->
        val representation = if (codePoint in PRINTABLE_ASCII_START..PRINTABLE_ASCII_END) {
            codePoint.toChar().toString()
        } else {
            "\\u{" + codePoint.toString(16).uppercase() + "}"
        }
        if (escaped.length + representation.length > MAX_DIAGNOSTIC_LENGTH) {
            truncated = true
            return@forEach
        }
        escaped.append(representation)
    }
    if (truncated) {
        while (escaped.length > MAX_DIAGNOSTIC_LENGTH - TRUNCATION_SUFFIX_LENGTH) {
            escaped.setLength(escaped.length - 1)
        }
        escaped.append("...")
    }
    return "[$escaped]"
}

private const val MAX_DIAGNOSTIC_LENGTH = 128
private const val TRUNCATION_SUFFIX_LENGTH = 3
private const val PRINTABLE_ASCII_START = 0x20
private const val PRINTABLE_ASCII_END = 0x7E
