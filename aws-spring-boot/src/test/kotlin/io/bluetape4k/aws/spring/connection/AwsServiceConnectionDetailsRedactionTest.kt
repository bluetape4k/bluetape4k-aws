package io.bluetape4k.aws.spring.connection

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.net.URI

class AwsServiceConnectionDetailsRedactionTest {

    @Test
    fun `snapshot copies values and redacts credentials in diagnostics`() {
        val accessKey = "access-key-that-must-not-leak"
        val secretKey = "secret-key-that-must-not-leak"
        val floci = mockk<io.bluetape4k.testcontainers.aws.FlociServer>(relaxed = true)
        var endpoint = URI.create("http://localhost:4566")
        var region = "us-east-1"
        var access = accessKey
        var secret = secretKey
        every { floci.awsEndpoint } answers { endpoint }
        every { floci.regionName } answers { region }
        every { floci.awsAccessKey } answers { access }
        every { floci.awsSecretKey } answers { secret }

        val values = snapshotAwsServiceConnection(floci, "s3").shouldNotBeNull()
        endpoint = URI.create("http://localhost:9999")
        region = "ap-northeast-2"
        access = "changed-access"
        secret = "changed-secret"

        values.endpoint shouldBeEqualTo URI.create("http://localhost:4566")
        values.region shouldBeEqualTo "us-east-1"
        values.accessKey shouldBeEqualTo accessKey
        values.secretKey shouldBeEqualTo secretKey
        values.toString().contains(accessKey).shouldBeFalse()
        values.toString().contains(secretKey).shouldBeFalse()
        values.toString() shouldBeEqualTo
            "AwsServiceConnectionValues(endpoint=http://localhost:4566, region=us-east-1, " +
            "accessKey=[REDACTED], secretKey=[REDACTED])"
    }

    @Test
    fun `configuration exception keeps immutable safe fields and no raw cause`() {
        val original = linkedSetOf("s3")
        val error = AwsServiceConnectionConfigurationException(
            reason = AwsServiceConnectionConfigurationException.Reason.CREDENTIAL_CONFLICT,
            serviceNames = original,
            candidateCount = 2,
            causeSummary = "secret-key-value",
        )
        original += "sqs"

        error.reason shouldBeEqualTo AwsServiceConnectionConfigurationException.Reason.CREDENTIAL_CONFLICT
        error.serviceNames shouldBeEqualTo setOf("s3")
        error.candidateCount shouldBeEqualTo 2
        error.cause.shouldBeNull()
        error.suppressed.size shouldBeEqualTo 0
        error.message.orEmpty().contains("secret-key-value").shouldBeFalse()
        @Suppress("UNCHECKED_CAST")
        (error.serviceNames as? MutableSet<String>)?.let { mutable ->
            try {
                mutable.add("sqs")
                throw AssertionError("serviceNames must be immutable")
            } catch (_: UnsupportedOperationException) {
                // expected immutable view
            }
        }
    }

    @Test
    fun `malformed details message contains only stable reason fields`() {
        val error = assertFailsWith<AwsServiceConnectionConfigurationException> {
            throw malformedDetails("dynamodb", 1)
        }
        error.reason shouldBeEqualTo AwsServiceConnectionConfigurationException.Reason.MALFORMED_DETAILS
        error.message.orEmpty() shouldBeEqualTo
            "AWS ServiceConnection configuration failed: reason=MALFORMED_DETAILS, " +
            "services=[dynamodb], candidates=1"
        error.message.orEmpty().contains("endpoint").shouldBeFalse()
        error.message.orEmpty().contains("credential").shouldBeFalse()
        error.message.orEmpty().contains("secret").shouldBeFalse()
        error.cause.shouldBeNull()
        error.suppressed.isEmpty().shouldBeTrue()
    }
}
