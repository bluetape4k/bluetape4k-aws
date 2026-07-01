package io.bluetape4k.aws.ktor

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.ktor.testing.shouldHaveStatus
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
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

    @Test
    fun `core plugin can install bluetape4k ktor baseline`() = testApplication {
        application {
            install(AwsKtorCore) {
                ktorCore()
            }
        }

        startApplication()

        client.get("/healthz") shouldHaveStatus HttpStatusCode.OK
        client.get("/readyz") shouldHaveStatus HttpStatusCode.OK
    }

    @Test
    fun `core plugin stores S3 Control customizers`() = testApplication {
        val customizer = AwsKtorS3ControlAsyncClientCustomizer { builder ->
            builder.overrideConfiguration { it.putHeader("x-test", "s3control") }
        }

        application {
            install(AwsKtorCore) {
                s3ControlAsyncClient(customizer)
            }
        }

        startApplication()

        application.awsKtorDefaults().s3ControlAsyncClientCustomizers.single() shouldBeSameInstanceAs customizer
    }

    @Test
    fun `core plugin stores S3 Vectors customizers`() = testApplication {
        val customizer = AwsKtorS3VectorsAsyncClientCustomizer { builder ->
            builder.overrideConfiguration { it.putHeader("x-test", "s3vectors") }
        }

        application {
            install(AwsKtorCore) {
                s3VectorsAsyncClient(customizer)
            }
        }

        startApplication()

        application.awsKtorDefaults().s3VectorsAsyncClientCustomizers.single() shouldBeSameInstanceAs customizer
    }

    @Test
    fun `core plugin stores SES v2 customizers`() = testApplication {
        val customizer = AwsKtorSesV2AsyncClientCustomizer { builder ->
            builder.overrideConfiguration { it.putHeader("x-test", "sesv2") }
        }

        application {
            install(AwsKtorCore) {
                sesV2AsyncClient(customizer)
            }
        }

        startApplication()

        application.awsKtorDefaults().sesV2AsyncClientCustomizers.single() shouldBeSameInstanceAs customizer
    }

    @Test
    fun `core plugin stores SNS customizers`() = testApplication {
        val customizer = AwsKtorSnsAsyncClientCustomizer { builder ->
            builder.overrideConfiguration { it.putHeader("x-test", "sns") }
        }

        application {
            install(AwsKtorCore) {
                snsAsyncClient(customizer)
            }
        }

        startApplication()

        application.awsKtorDefaults().snsAsyncClientCustomizers.single() shouldBeSameInstanceAs customizer
    }

    @Test
    fun `core plugin stores Kinesis customizers`() = testApplication {
        val customizer = AwsKtorKinesisAsyncClientCustomizer { builder ->
            builder.overrideConfiguration { it.putHeader("x-test", "kinesis") }
        }

        application {
            install(AwsKtorCore) {
                kinesisAsyncClient(customizer)
            }
        }

        startApplication()

        application.awsKtorDefaults().kinesisAsyncClientCustomizers.single() shouldBeSameInstanceAs customizer
    }

    @Test
    fun `core plugin stores STS customizers`() = testApplication {
        val customizer = AwsKtorStsAsyncClientCustomizer { builder ->
            builder.overrideConfiguration { it.putHeader("x-test", "sts") }
        }

        application {
            install(AwsKtorCore) {
                stsAsyncClient(customizer)
            }
        }

        startApplication()

        application.awsKtorDefaults().stsAsyncClientCustomizers.single() shouldBeSameInstanceAs customizer
    }
}
