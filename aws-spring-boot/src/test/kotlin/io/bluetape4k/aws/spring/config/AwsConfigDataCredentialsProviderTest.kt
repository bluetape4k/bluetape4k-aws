package io.bluetape4k.aws.spring.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.aws.spring.AwsProperties
import io.bluetape4k.codec.Base58
import org.junit.jupiter.api.Test
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.WebIdentityTokenFileCredentialsProvider
import java.nio.file.Files

class AwsConfigDataCredentialsProviderTest {

    @Test
    fun `disabled web identity uses the SDK default chain`() {
        AwsProperties().configDataCredentialsProvider()
            .shouldBeInstanceOf(DefaultCredentialsProvider::class)
    }

    @Test
    fun `enabled web identity validates every configured value before provider creation`() {
        val role = "arn:aws:iam::123456789012:role/${Base58.randomString(16)}"
        val session = "session-${Base58.randomString(16)}"
        val tokenFile = Files.createTempFile("aws-${Base58.randomString(16)}", ".token")
        try {
            val properties = AwsProperties(
                credentials = AwsProperties.Credentials(
                    webIdentity = AwsProperties.WebIdentity(
                        enabled = true,
                        roleArn = role,
                        roleSessionName = session,
                        tokenFile = tokenFile,
                    ),
                ),
            )

            properties.configDataCredentialsProvider()
                .shouldBeInstanceOf<WebIdentityTokenFileCredentialsProvider>()

            val malformed = properties.copy(
                credentials = properties.credentials.copy(
                    webIdentity = properties.credentials.webIdentity.copy(roleSessionName = " "),
                ),
            )
            val error = assertFailsWith<IllegalArgumentException> {
                malformed.configDataCredentialsProvider()
            }
            error.message.orEmpty() shouldContain "configuration is invalid"
            error.message.orEmpty() shouldNotContain role
            error.message.orEmpty() shouldNotContain tokenFile.toString()
        } finally {
            Files.deleteIfExists(tokenFile)
        }
    }
}
