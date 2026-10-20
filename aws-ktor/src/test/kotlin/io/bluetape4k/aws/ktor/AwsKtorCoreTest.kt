package io.bluetape4k.aws.ktor

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.ktor.testing.shouldHaveStatus
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import org.junit.jupiter.api.Test
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AwsKtorCoreTest {

    companion object: KLogging()

    @Test
    fun `core config toString redacts endpoint and credential provider`() {
        val provider = object: software.amazon.awssdk.auth.credentials.AwsCredentialsProvider {
            override fun resolveCredentials() = AwsBasicCredentials.create("access-key", "credential-secret")
            override fun toString() = "provider-secret"
        }
        val config = AwsKtorCoreConfig().apply {
            region = "ap-northeast-2"
            endpointOverride = Url(
                "https://endpoint-user:endpoint-password@example.com/private-path?token=endpoint-token",
            )
            javaCredentialsProvider = provider
        }

        val rendered = config.toString()
        listOf(
            "credential-secret",
            "provider-secret",
            "endpoint-user",
            "endpoint-password",
            "private-path",
            "endpoint-token",
        )
            .forEach { rendered shouldNotContain it }
    }

    @Test
    fun `application stores shared AWS defaults`() = testApplication {
        val javaCredentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("ak", "sk"))
        val kotlinCredentials = mockk<CredentialsProvider>()
        val smithyHttpClient = mockk<HttpClientEngine>()
        val clock = Clock.fixed(Instant.parse("2026-05-26T01:02:03Z"), ZoneOffset.UTC)

        application {
            install(AwsKtorCore) {
                region = "ap-northeast-2"
                endpointOverride = Url("http://localhost:4566")
                allowInsecureLocalHttpEndpoint = true
                javaCredentialsProvider = javaCredentials
                kotlinCredentialsProvider = kotlinCredentials
                kotlinHttpClient = smithyHttpClient
                signingClock = clock
            }
        }

        startApplication()

        val defaults = application.awsKtorDefaults()

        log.debug { "defaults=$defaults" }
        defaults.region shouldBeEqualTo "ap-northeast-2"
        defaults.endpointOverride.toString() shouldBeEqualTo "http://localhost:4566"
        defaults.allowInsecureLocalHttpEndpoint shouldBeEqualTo true
        defaults.javaCredentialsProvider shouldBeSameInstanceAs javaCredentials
        defaults.kotlinCredentialsProvider shouldBeSameInstanceAs kotlinCredentials
        defaults.kotlinHttpClient shouldBeSameInstanceAs smithyHttpClient
        defaults.signingClock shouldBeSameInstanceAs clock
    }

    @Test
    fun `core stores AWS Kotlin service customizers in registration order`() = testApplication {
        val cloudWatchFirst = AwsKtorCloudWatchClientCustomizer { }
        val cloudWatchSecond = AwsKtorCloudWatchClientCustomizer { }
        val cloudWatchLogs = AwsKtorCloudWatchLogsClientCustomizer { }
        val eventBridge = AwsKtorEventBridgeClientCustomizer { }
        val kinesis = AwsKtorKinesisClientCustomizer { }
        val s3Control = AwsKtorS3ControlClientCustomizer { }
        val s3Vectors = AwsKtorS3VectorsClientCustomizer { }
        val sesV2 = AwsKtorSesV2ClientCustomizer { }
        val sns = AwsKtorSnsClientCustomizer { }
        val sqs = AwsKtorSqsClientCustomizer { }
        val sts = AwsKtorStsClientCustomizer { }

        application {
            install(AwsKtorCore) {
                cloudWatchClient(cloudWatchFirst)
                cloudWatchClient(cloudWatchSecond)
                cloudWatchLogsClient(cloudWatchLogs)
                eventBridgeClient(eventBridge)
                kinesisClient(kinesis)
                s3ControlClient(s3Control)
                s3VectorsClient(s3Vectors)
                sesV2Client(sesV2)
                snsClient(sns)
                sqsClient(sqs)
                stsClient(sts)
            }
        }
        startApplication()

        val defaults = application.awsKtorDefaults()
        defaults.cloudWatchClientCustomizers shouldBeEqualTo listOf(cloudWatchFirst, cloudWatchSecond)
        defaults.cloudWatchLogsClientCustomizers shouldBeEqualTo listOf(cloudWatchLogs)
        defaults.eventBridgeClientCustomizers shouldBeEqualTo listOf(eventBridge)
        defaults.kinesisClientCustomizers shouldBeEqualTo listOf(kinesis)
        defaults.s3ControlClientCustomizers shouldBeEqualTo listOf(s3Control)
        defaults.s3VectorsClientCustomizers shouldBeEqualTo listOf(s3Vectors)
        defaults.sesV2ClientCustomizers shouldBeEqualTo listOf(sesV2)
        defaults.snsClientCustomizers shouldBeEqualTo listOf(sns)
        defaults.sqsClientCustomizers shouldBeEqualTo listOf(sqs)
        defaults.stsClientCustomizers shouldBeEqualTo listOf(sts)
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
