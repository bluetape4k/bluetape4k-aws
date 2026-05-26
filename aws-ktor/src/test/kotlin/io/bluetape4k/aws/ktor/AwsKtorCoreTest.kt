package io.bluetape4k.aws.ktor

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.ktor.http.Url
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AwsKtorCoreTest {

    @Test
    fun `application stores shared AWS defaults`() = testApplication {
        val javaCredentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("ak", "sk"))
        val clock = Clock.fixed(Instant.parse("2026-05-26T01:02:03Z"), ZoneOffset.UTC)

        application {
            install(AwsKtorCore) {
                region = "ap-northeast-2"
                endpointOverride = Url("http://localhost:4566")
                javaCredentialsProvider = javaCredentials
                signingClock = clock
            }
        }

        startApplication()
        val defaults = application.awsKtorDefaults()
        defaults.region shouldBeEqualTo "ap-northeast-2"
        defaults.endpointOverride.toString() shouldBeEqualTo "http://localhost:4566"
        defaults.javaCredentialsProvider shouldBeSameInstanceAs javaCredentials
        defaults.signingClock shouldBeSameInstanceAs clock
    }

    @Test
    fun `application returns empty defaults when core plugin is absent`() = testApplication {
        application.awsKtorDefaults() shouldBeEqualTo AwsKtorDefaults()
    }
}
