package io.bluetape4k.aws.rds

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.aws.exceptions.AwsBluetapeException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.rds.RdsUtilities
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AwsRdsIamAuthTokenTest {

    @Test
    fun `auth token redacts diagnostic output`() {
        val token = awsRdsIamAuthTokenOf("raw-rds-token")

        token.reveal() shouldBeEqualTo "raw-rds-token"
        token.toString() shouldBeEqualTo AwsRdsIamAuthToken.REDACTED
    }

    @Test
    fun `auth token rejects blank value`() {
        assertFailsWith<IllegalArgumentException> {
            awsRdsIamAuthTokenOf(" \t")
        }
    }

    @Test
    fun `request validates required signing fields`() {
        assertFailsWith<IllegalArgumentException> {
            AwsRdsIamAuthTokenRequest(
                region = "",
                hostname = "database.example.com",
                port = 5432,
                username = "app_user",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AwsRdsIamAuthTokenRequest(
                region = "ap-northeast-2",
                hostname = " ",
                port = 5432,
                username = "app_user",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AwsRdsIamAuthTokenRequest(
                region = "ap-northeast-2",
                hostname = "database.example.com",
                port = 0,
                username = "app_user",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AwsRdsIamAuthTokenRequest(
                region = "ap-northeast-2",
                hostname = "database.example.com",
                port = 5432,
                username = "\t",
            )
        }
    }

    @Test
    fun `sdk generator maps request to AWS SDK request`() {
        var captured: GenerateAuthenticationTokenRequest? = null
        val generator = AwsSdkRdsIamAuthTokenGenerator(
            rdsUtilities = object: RdsUtilities {
                override fun generateAuthenticationToken(request: GenerateAuthenticationTokenRequest): String {
                    captured = request
                    return "generated-token"
                }
            },
        )

        val token = generator.generate(
            AwsRdsIamAuthTokenRequest(
                region = "ap-northeast-2",
                hostname = "database-1.cluster-example.ap-northeast-2.rds.amazonaws.com",
                port = 5432,
                username = "app_user",
            ),
        )

        token.reveal() shouldBeEqualTo "generated-token"
        token.toString() shouldBeEqualTo AwsRdsIamAuthToken.REDACTED
        val request = requireNotNull(captured)
        request.region() shouldBeEqualTo Region.AP_NORTHEAST_2
        request.hostname() shouldBeEqualTo "database-1.cluster-example.ap-northeast-2.rds.amazonaws.com"
        request.port() shouldBeEqualTo 5432
        request.username() shouldBeEqualTo "app_user"
    }

    @Test
    fun `sdk generator wraps failures without leaking token material`() {
        val generator = AwsSdkRdsIamAuthTokenGenerator(
            rdsUtilities = object: RdsUtilities {
                override fun generateAuthenticationToken(request: GenerateAuthenticationTokenRequest): String =
                    error("credential chain failed with raw-token")
            },
        )

        val error = assertFailsWith<AwsRdsIamAuthTokenException> {
            generator.generate(
                AwsRdsIamAuthTokenRequest(
                    region = "ap-northeast-2",
                    hostname = "database.example.com",
                    port = 5432,
                    username = "app_user",
                ),
            )
        }

        (error is AwsBluetapeException).shouldBeTrue()
        error.message.orEmpty() shouldContain "database.example.com:5432"
        error.message.orEmpty().contains("raw-token").shouldBeFalse()
    }
}
