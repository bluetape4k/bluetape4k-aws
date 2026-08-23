package io.bluetape4k.aws.spring.s3

import org.springframework.util.AntPathMatcher
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal enum class S3WildcardKind {
    STAR,
    DOUBLE_STAR,
    QUESTION,
}

internal sealed interface S3PatternToken {
    data class Literal(val value: String): S3PatternToken
    data class Wildcard(val kind: S3WildcardKind): S3PatternToken
}

internal data class S3Pattern(
    val bucket: String,
    val prefix: String,
    val tokens: List<S3PatternToken>,
) {

    val hasWildcards: Boolean
        get() = tokens.any { it is S3PatternToken.Wildcard }

    fun prepareMatcher(): S3PatternMatcher =
        AntS3PatternMatcher(tokens)
}

internal fun interface S3PatternMatcher {
    fun matches(key: String): Boolean
}

/**
 * S3 URI의 exact 위치와 wildcard pattern을 네트워크 호출 전에 검증한다.
 * bucket은 항상 하나의 literal authority이고, pattern listing은 첫 wildcard
 * 앞에 비어 있지 않은 prefix가 있을 때만 허용된다.
 */
internal class S3ResourceLocationParser {

    /**
     * exact `s3://bucket/key`를 파싱한다. raw wildcard와 빈 key는 거부하고,
     * percent escape는 strict UTF-8로 한 번만 decode한다.
     */
    fun parseExact(location: String): S3ObjectLocation {
        val parsed = parse(location, allowPatternQuestion = false)
        require(!parsed.tokens.any { it is S3PatternToken.Wildcard }) {
            "S3 exact location must not contain wildcard tokens."
        }
        return S3ObjectLocation(parsed.bucket, parsed.prefix)
    }

    /**
     * exact 또는 `*`, `?`, `**` pattern을 파싱한다. wildcard가 있으면 첫 wildcard
     * 앞의 literal prefix가 비어 있지 않아야 한다.
     */
    fun parsePattern(location: String): S3Pattern =
        parse(location, allowPatternQuestion = true)

    private fun parse(
        location: String,
        allowPatternQuestion: Boolean,
    ): S3Pattern {
        require(location.startsWith(S3_SCHEME, ignoreCase = true)) {
            "S3 location must start with s3://."
        }

        val remainder = location.substring(S3_SCHEME.length)
        val separator = remainder.indexOf('/')
        require(separator > 0) {
            "S3 location must contain one literal bucket and a non-empty key."
        }
        val rawBucket = remainder.substring(0, separator)
        val rawPath = remainder.substring(separator + 1)
        validateBucket(rawBucket)
        validatePathSyntax(rawPath, allowPatternQuestion)

        val decoded = decodePath(rawPath)
        val decodedText = decoded.joinToString(separator = "") { it.value.toString() }
        require(decodedText.isNotBlank()) {
            "S3 object key must not be empty."
        }

        val tokens = tokenize(decoded)
        val hasWildcards = tokens.any { it is S3PatternToken.Wildcard }
        if (hasWildcards) {
            val firstWildcard = tokens.indexOfFirst { it is S3PatternToken.Wildcard }
            val prefix = tokens
                .subList(0, firstWildcard)
                .joinToString(separator = "") { (it as S3PatternToken.Literal).value }
            require(prefix.isNotEmpty()) {
                "S3 wildcard patterns require a non-empty prefix."
            }
            return S3Pattern(rawBucket, prefix, tokens)
        }

        val key = tokens.joinToString(separator = "") { (it as S3PatternToken.Literal).value }
        require(key.isNotBlank()) {
            "S3 object key must not be empty."
        }
        return S3Pattern(rawBucket, key, tokens)
    }

    private fun validateBucket(bucket: String) {
        require(bucket.isNotBlank()) { "S3 bucket must not be blank." }
        require('%' !in bucket) { "S3 bucket must be a literal authority." }
        require("," !in bucket && "s3://" !in bucket.lowercase()) {
            "S3 location must contain one bucket only."
        }
        require(bucket.none { character ->
            character.isWhitespace() || character.isISOControl() || character in "@:*?[]/#"
        }) {
            "S3 bucket must be a single literal authority."
        }
    }

    private fun validatePathSyntax(path: String, allowPatternQuestion: Boolean) {
        require('#' !in path) { "S3 location must not contain a fragment." }
        require("s3://" !in path.lowercase()) {
            "S3 location must contain one bucket only."
        }
        if (!allowPatternQuestion) {
            require('?' !in path) { "S3 exact location must not contain a query or wildcard." }
        } else {
            val question = path.indexOf('?')
            if (question >= 0) {
                val suffix = path.substring(question + 1)
                require('=' !in suffix && '&' !in suffix) {
                    "S3 location must not contain a query."
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun tokenize(decoded: List<DecodedCharacter>): List<S3PatternToken> {
        val tokens = ArrayList<S3PatternToken>()
        val literal = StringBuilder()

        fun flushLiteral() {
            if (literal.isNotEmpty()) {
                tokens += S3PatternToken.Literal(literal.toString())
                literal.setLength(0)
            }
        }

        var index = 0
        while (index < decoded.size) {
            val item = decoded[index]
            when {
                !item.escaped && item.value == '*' -> {
                    flushLiteral()
                    if (index + 1 < decoded.size &&
                        !decoded[index + 1].escaped &&
                        decoded[index + 1].value == '*'
                    ) {
                        tokens += S3PatternToken.Wildcard(S3WildcardKind.DOUBLE_STAR)
                        index += 2
                    } else {
                        tokens += S3PatternToken.Wildcard(S3WildcardKind.STAR)
                        index++
                    }
                }

                !item.escaped && item.value == '?' -> {
                    flushLiteral()
                    tokens += S3PatternToken.Wildcard(S3WildcardKind.QUESTION)
                    index++
                }

                (!item.escaped && item.value == '[') || (!item.escaped && item.value == ']') -> {
                    throw IllegalArgumentException("S3 pattern character classes are not supported.")
                }

                else -> {
                    literal.append(item.value)
                    index++
                }
            }
        }
        flushLiteral()
        return tokens
    }

    private fun decodePath(path: String): List<DecodedCharacter> {
        val decoded = ArrayList<DecodedCharacter>()
        var index = 0
        while (index < path.length) {
            if (path[index] != '%') {
                val codePoint = path.codePointAt(index)
                String(Character.toChars(codePoint)).forEach { character ->
                    decoded += DecodedCharacter(character, escaped = false)
                }
                index += Character.charCount(codePoint)
                continue
            }

            val bytes = ArrayList<Byte>()
            while (index < path.length && path[index] == '%') {
                require(index + PERCENT_LOW_OFFSET < path.length) { "Malformed S3 percent escape." }
                val high = hex(path[index + PERCENT_HEX_OFFSET])
                val low = hex(path[index + PERCENT_LOW_OFFSET])
                bytes += ((high shl BYTE_HEX_SHIFT) or low).toByte()
                index += PERCENT_ESCAPE_LENGTH
            }
            val value = strictUtf8(bytes.toByteArray())
            value.forEach { character ->
                decoded += DecodedCharacter(character, escaped = true)
            }
        }
        return decoded
    }

    private fun strictUtf8(bytes: ByteArray): String =
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (cause: CharacterCodingException) {
            throw IllegalArgumentException("Malformed S3 UTF-8 percent escape.", cause)
        }

    private fun hex(character: Char): Int =
        character.digitToIntOrNull(HEX_RADIX)
            ?: throw IllegalArgumentException("Malformed S3 percent escape.")

    private data class DecodedCharacter(
        val value: Char,
        val escaped: Boolean,
    )

    private companion object {
        const val S3_SCHEME = "s3://"
        const val PERCENT_ESCAPE_LENGTH = 3
        const val PERCENT_HEX_OFFSET = 1
        const val PERCENT_LOW_OFFSET = 2
        const val BYTE_HEX_SHIFT = 4
        const val HEX_RADIX = 16
    }
}

private class AntS3PatternMatcher(
    private val tokens: List<S3PatternToken>,
): S3PatternMatcher {

    private val pathMatcher = AntPathMatcher()
    private val literalCharacters = tokens
        .asSequence()
        .filterIsInstance<S3PatternToken.Literal>()
        .flatMap { it.value.asSequence() }
        .toSet()

    override fun matches(key: String): Boolean {
        val sentinels = selectSentinels(key)
            ?: throw IllegalStateException("No safe S3 wildcard sentinel is available.")
        val renderedPattern = renderPattern(sentinels)
        val renderedKey = key
            .replace("*", sentinels.star)
            .replace("?", sentinels.question)
        return pathMatcher.match(renderedPattern, renderedKey)
    }

    private fun renderPattern(sentinels: SentinelPair): String =
        buildString {
            tokens.forEach { token ->
                when (token) {
                    is S3PatternToken.Literal -> append(
                        token.value
                            .replace("*", sentinels.star)
                            .replace("?", sentinels.question),
                    )
                    is S3PatternToken.Wildcard -> append(
                        when (token.kind) {
                            S3WildcardKind.STAR -> "*"
                            S3WildcardKind.DOUBLE_STAR -> "**"
                            S3WildcardKind.QUESTION -> "?"
                        },
                    )
                }
            }
        }

    private fun selectSentinels(key: String): SentinelPair? {
        val available = privateUseSentinels().filter { candidate ->
            candidate.none { it in literalCharacters } && !key.contains(candidate)
        }
        val selected = available.take(SENTINEL_COUNT).toList()
        return selected.takeIf { it.size == SENTINEL_COUNT }?.let {
            SentinelPair(star = it[0], question = it[1])
        }
    }

    private fun privateUseSentinels(): Sequence<String> = sequence {
        for (codePoint in BMP_PRIVATE_USE_START..BMP_PRIVATE_USE_END) {
            yield(codePoint.toChar().toString())
        }
        for (codePoint in SUPPLEMENTARY_PRIVATE_USE_START..SUPPLEMENTARY_PRIVATE_USE_END) {
            yield(String(Character.toChars(codePoint)))
        }
    }

    private data class SentinelPair(
        val star: String,
        val question: String,
    )

    private companion object {
        const val SENTINEL_COUNT = 2
        const val BMP_PRIVATE_USE_START = 0xE000
        const val BMP_PRIVATE_USE_END = 0xF8FF
        const val SUPPLEMENTARY_PRIVATE_USE_START = 0xF0000
        const val SUPPLEMENTARY_PRIVATE_USE_END = 0xFFFFD
    }
}
