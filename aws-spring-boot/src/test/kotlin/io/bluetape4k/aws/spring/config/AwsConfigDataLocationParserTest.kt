package io.bluetape4k.aws.spring.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.context.config.ConfigDataLocation

class AwsConfigDataLocationParserTest {

    private val parser = AwsConfigDataLocationParser()

    @Test
    fun `parses optional S3 location and decodes each component once`() {
        val parsed = parser.parse(
            ConfigDataLocation.of(
                "optional:aws-s3:/config%20bucket/application%20.yml?prefix=app%2520prod&format=yaml",
            ),
        )

        parsed.backend shouldBeEqualTo AwsConfigDataBackend.S3
        parsed.optional shouldBeEqualTo true
        parsed.source shouldBeEqualTo AwsConfigDataSource.S3(
            bucket = "config bucket",
            key = "application .yml",
            prefix = "app%20prod",
            format = io.bluetape4k.aws.spring.s3.S3ConfigFormat.YAML,
        )
        parsed.options shouldBeEqualTo mapOf("format" to "yaml", "prefix" to "app%20prod")
    }

    @Test
    fun `parses parameter store and secrets manager options`() {
        val parameter = parser.parse(
            ConfigDataLocation.of(
                "aws-parameterstore:/application?prefix=app&recursive=false&withDecryption=true",
            ),
        )
        val secret = parser.parse(
            ConfigDataLocation.of(
                "optional:aws-secretsmanager:prod%2Fdatabase?prefix=app&format=text",
            ),
        )

        parameter.source shouldBeEqualTo AwsConfigDataSource.ParameterStore(
            path = "/application",
            prefix = "app",
            recursive = false,
            withDecryption = true,
        )
        secret.source shouldBeEqualTo AwsConfigDataSource.SecretsManager(
            secretId = "prod/database",
            prefix = "app",
            format = io.bluetape4k.aws.spring.secretsmanager.SecretFormat.TEXT,
        )
        secret.optional shouldBeEqualTo true
    }

    @Test
    fun `rejects duplicate unknown empty and malformed options`() {
        listOf(
            "aws-s3:/bucket/key?format=yaml&format=json",
            "aws-s3:/bucket/key?unknown=value",
            "aws-s3:/bucket/key?prefix=",
            "aws-parameterstore:/path?recursive=maybe",
            "aws-s3:/bucket/key?format=toml",
            "aws-secretsmanager:secret?format=text",
        ).forEach { location ->
            assertThrows<IllegalArgumentException> {
                parser.parse(ConfigDataLocation.of(location))
            }
        }
    }

    @Test
    fun `rejects control characters and empty sources`() {
        listOf(
            "aws-s3:/bucket/\nkey",
            "aws-parameterstore:/path%00suffix",
            "aws-secretsmanager:secret%0Dvalue",
            "aws-s3:/",
            "aws-secretsmanager:",
        ).forEach { location ->
            assertThrows<IllegalArgumentException> {
                parser.parse(ConfigDataLocation.of(location))
            }
        }
    }

    @Test
    fun `accepts percent encoded query separators without a second decode`() {
        val parsed = parser.parse(
            ConfigDataLocation.of(
                "aws-parameterstore:/app?prefix=tenant%26prod&recursive=true",
            ),
        )

        parsed.source shouldBeEqualTo AwsConfigDataSource.ParameterStore(
            path = "/app",
            prefix = "tenant&prod",
            recursive = true,
            withDecryption = true,
        )
    }

    @Test
    fun `keeps parser errors free of raw secret identifiers`() {
        val secretId = "arn:aws:secretsmanager:us-east-1:123456789012:secret/prod-secret"
        val error = assertThrows<IllegalArgumentException> {
            parser.parse(ConfigDataLocation.of("aws-secretsmanager:$secretId?format=text"))
        }

        error.toString() shouldNotContain secretId
        error.message.orEmpty() shouldContain "prefix"
    }
}
