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
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.collections.Attributes
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AwsKtorCoreTest {

    companion object: KLogging()

    @Test
    fun `core config toString redacts endpoint and credential provider`() {
        val provider = object: CredentialsProvider {
            override suspend fun resolve(attributes: Attributes): Credentials = error("unused")

            override fun toString() = "provider-secret"
        }
        val config = AwsKtorCoreConfig().apply {
            region = "ap-northeast-2"
            endpointOverride = Url(
                "https://endpoint-user:endpoint-password@example.com/private-path?token=endpoint-token",
            )
            kotlinCredentialsProvider = provider
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
        val kotlinCredentials = mockk<CredentialsProvider>()
        val smithyHttpClient = mockk<HttpClientEngine>()
        val clock = Clock.fixed(Instant.parse("2026-05-26T01:02:03Z"), ZoneOffset.UTC)

        application {
            install(AwsKtorCore) {
                region = "ap-northeast-2"
                endpointOverride = Url("http://localhost:4566")
                allowInsecureLocalHttpEndpoint = true
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

}
