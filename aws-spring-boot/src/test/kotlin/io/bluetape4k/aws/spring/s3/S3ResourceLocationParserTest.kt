package io.bluetape4k.aws.spring.s3

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import org.junit.jupiter.api.Test

class S3ResourceLocationParserTest {

    private val parser = S3ResourceLocationParser()

    @Test
    fun `parse exact location with case insensitive scheme and preserved key characters`() {
        parser.parseExact("S3://config-bucket/config/raw+name%20with%2Fslash/") shouldBeEqualTo
            S3ObjectLocation("config-bucket", "config/raw+name with/slash/")
    }

    @Test
    fun `parse exact location preserves escaped wildcard and bracket as literals`() {
        parser.parseExact("s3://bucket/config/%2A-%3F-%5B-%5D") shouldBeEqualTo
            S3ObjectLocation("bucket", "config/*-?-[-]")
    }

    @Test
    fun `parse pattern preserves wildcard tokens and computes literal prefix`() {
        val pattern = parser.parsePattern("s3://bucket/config/**/application-?.yml")

        pattern.bucket shouldBeEqualTo "bucket"
        pattern.prefix shouldBeEqualTo "config/"
        pattern.hasWildcards.shouldBeTrue()
        pattern.tokens shouldContain S3PatternToken.Wildcard(S3WildcardKind.DOUBLE_STAR)
        pattern.tokens shouldContain S3PatternToken.Wildcard(S3WildcardKind.QUESTION)
    }

    @Test
    fun `parse exact rejects raw wildcard but pattern accepts supported wildcard`() {
        assertFailsWith<IllegalArgumentException> {
            parser.parseExact("s3://bucket/config/*.json")
        }

        parser.parsePattern("s3://bucket/config/*.json").hasWildcards.shouldBeTrue()
    }

    @Test
    fun `parse pattern keeps escaped wildcard in literal prefix`() {
        val pattern = parser.parsePattern("s3://bucket/config/%2A/*.json")

        pattern.prefix shouldBeEqualTo "config/*/"
        pattern.tokens.filterIsInstance<S3PatternToken.Literal>()
            .any { it.value.contains("*") }.shouldBeTrue()
    }

    @Test
    fun `parse pattern rejects empty bucket prefix root listing`() {
        listOf(
            "s3://bucket/*.json",
            "s3://bucket/**",
            "s3://bucket/?",
        ).forEach { location ->
            val error = assertFailsWith<IllegalArgumentException> {
                parser.parsePattern(location)
            }
            error.message shouldContain "prefix"
        }
    }

    @Test
    fun `parse pattern rejects unsupported authority and character class syntax`() {
        listOf(
            "s3://bucket-*/config/*.json",
            "s3://bucket:443/config/*.json",
            "s3://user@bucket/config/*.json",
            "s3://bucket#fragment/config/*.json",
            "s3://bucket-a/config/*.json,s3://bucket-b/config/*.json",
            "s3://bucket/config/[ab].json",
        ).forEach { location ->
            assertFailsWith<IllegalArgumentException> {
                parser.parsePattern(location)
            }
        }
    }

    @Test
    fun `parse rejects query fragment empty key malformed escapes and invalid utf8`() {
        listOf(
            "s3://bucket/config/key?secret=value",
            "s3://bucket/config/key#fragment",
            "s3://bucket",
            "s3://bucket/",
            "s3://bucket/%20",
            "s3://bucket/config/%",
            "s3://bucket/config/%2",
            "s3://bucket/config/%C3%28",
        ).forEach { location ->
            assertFailsWith<IllegalArgumentException> {
                parser.parseExact(location)
            }
        }
    }

    @Test
    fun `parse pattern without wildcards is an exact pattern`() {
        val pattern = parser.parsePattern("s3://bucket/config/application.yml")

        pattern.hasWildcards.shouldBeFalse()
        pattern.prefix shouldBeEqualTo "config/application.yml"
        pattern.tokens shouldBeEqualTo listOf(S3PatternToken.Literal("config/application.yml"))
    }
}
