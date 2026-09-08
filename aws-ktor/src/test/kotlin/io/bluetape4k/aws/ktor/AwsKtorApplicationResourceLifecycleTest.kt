package io.bluetape4k.aws.ktor

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.aws.ktor.cloudwatch.CloudWatchKtorPluginConfig
import io.bluetape4k.aws.ktor.cloudwatch.CloudWatchKtorRuntime
import io.bluetape4k.aws.ktor.eventbridge.EventBridgeKtorPluginConfig
import io.bluetape4k.aws.ktor.eventbridge.EventBridgeKtorRuntime
import io.bluetape4k.aws.ktor.imds.ImdsKtorPluginConfig
import io.bluetape4k.aws.ktor.imds.ImdsKtorRuntime
import io.bluetape4k.aws.ktor.kinesis.KinesisKtorPluginConfig
import io.bluetape4k.aws.ktor.kinesis.KinesisKtorRuntime
import io.bluetape4k.aws.ktor.s3.accessgrants.S3AccessGrantsKtorRuntime
import io.bluetape4k.aws.ktor.s3.accessgrants.S3AccessGrantsKtorPluginConfig
import io.bluetape4k.aws.ktor.s3vectors.S3VectorsKtorPluginConfig
import io.bluetape4k.aws.ktor.s3vectors.S3VectorsKtorRuntime
import io.bluetape4k.aws.ktor.ses.SesKtorPluginConfig
import io.bluetape4k.aws.ktor.ses.SesKtorRuntime
import io.bluetape4k.aws.ktor.sns.SnsKtorPluginConfig
import io.bluetape4k.aws.ktor.sns.SnsKtorRuntime
import io.bluetape4k.aws.ktor.sts.StsKtorPluginConfig
import io.bluetape4k.aws.ktor.sts.StsKtorPlugin
import io.bluetape4k.aws.ktor.sts.StsKtorRuntime
import io.bluetape4k.ktor.core.ApplicationResourceRegistry
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.Test
import software.amazon.awssdk.imds.Ec2MetadataAsyncClient
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.eventbridge.EventBridgeAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.s3control.S3ControlAsyncClient
import software.amazon.awssdk.services.s3vectors.S3VectorsAsyncClient
import software.amazon.awssdk.services.sesv2.SesV2AsyncClient
import software.amazon.awssdk.services.sts.StsAsyncClient
import software.amazon.awssdk.services.sts.StsAsyncClientBuilder
import software.amazon.awssdk.services.sns.SnsAsyncClient
import java.util.concurrent.atomic.AtomicInteger

class AwsKtorApplicationResourceLifecycleTest {

    @Test
    fun `plugin owned client remains open during ApplicationStopping`() {
        val builder = mockk<StsAsyncClientBuilder>(relaxed = true)
        val client = mockk<StsAsyncClient>(relaxed = true)
        val closeCount = AtomicInteger()
        every { client.close() } answers { closeCount.incrementAndGet() }
        var closeCountAtStopping = -1

        mockkStatic(StsAsyncClient::class)
        try {
            every { StsAsyncClient.builder() } returns builder
            every { builder.build() } returns client

            testApplication {
                application {
                    install(StsKtorPlugin)
                    monitor.subscribe(ApplicationStopping) {
                        closeCountAtStopping = closeCount.get()
                    }
                }

                startApplication()
            }

            closeCountAtStopping shouldBeEqualTo 0
            closeCount.get() shouldBeEqualTo 1
        } finally {
            unmockkStatic(StsAsyncClient::class)
        }
    }

    @Test
    fun `registry closes all simple plugin owned clients once`() {
        val registry = ApplicationResourceRegistry()
        val stsClient = mockk<StsAsyncClient>(relaxed = true)
        val snsClient = mockk<SnsAsyncClient>(relaxed = true)
        val sesClient = mockk<SesV2AsyncClient>(relaxed = true)
        val eventBridgeClient = mockk<EventBridgeAsyncClient>(relaxed = true)
        val kinesisClient = mockk<KinesisAsyncClient>(relaxed = true)
        val s3VectorsClient = mockk<S3VectorsAsyncClient>(relaxed = true)
        val cloudWatchClient = mockk<CloudWatchAsyncClient>(relaxed = true)
        val imdsClient = mockk<Ec2MetadataAsyncClient>(relaxed = true)
        val s3ControlClient = mockk<S3ControlAsyncClient>(relaxed = true)

        StsKtorRuntime(mockk(relaxed = true), stsClient).also {
            it.registerApplicationResources(registry)
            it.registerApplicationResources(registry)
        }
        SnsKtorRuntime(mockk(relaxed = true), ownedClient = snsClient).also {
            it.registerApplicationResources(registry)
        }
        SesKtorRuntime(mockk(relaxed = true), sesClient).also {
            it.registerApplicationResources(registry)
        }
        EventBridgeKtorRuntime(mockk(relaxed = true), eventBridgeClient).also {
            it.registerApplicationResources(registry)
        }
        KinesisKtorRuntime(mockk(relaxed = true), kinesisClient).also {
            it.registerApplicationResources(registry)
        }
        S3VectorsKtorRuntime(mockk(relaxed = true), s3VectorsClient).also {
            it.registerApplicationResources(registry)
        }
        CloudWatchKtorRuntime(mockk(relaxed = true), cloudWatchClient).also {
            it.registerApplicationResources(registry)
        }
        ImdsKtorRuntime(mockk(relaxed = true), imdsClient).also {
            it.registerApplicationResources(registry)
        }
        S3AccessGrantsKtorRuntime(mockk(relaxed = true), s3ControlClient).also {
            it.registerApplicationResources(registry)
        }

        registry.close()
        registry.close()

        verify(exactly = 1) { stsClient.close() }
        verify(exactly = 1) { snsClient.close() }
        verify(exactly = 1) { sesClient.close() }
        verify(exactly = 1) { eventBridgeClient.close() }
        verify(exactly = 1) { kinesisClient.close() }
        verify(exactly = 1) { s3VectorsClient.close() }
        verify(exactly = 1) { cloudWatchClient.close() }
        verify(exactly = 1) { imdsClient.close() }
        verify(exactly = 1) { s3ControlClient.close() }
    }

    @Test
    fun `caller owned clients are not registered for any simple plugin`() {
        val registry = ApplicationResourceRegistry()
        val stsClient = mockk<StsAsyncClient>(relaxed = true)
        val snsClient = mockk<SnsAsyncClient>(relaxed = true)
        val sesClient = mockk<SesV2AsyncClient>(relaxed = true)
        val eventBridgeClient = mockk<EventBridgeAsyncClient>(relaxed = true)
        val kinesisClient = mockk<KinesisAsyncClient>(relaxed = true)
        val s3VectorsClient = mockk<S3VectorsAsyncClient>(relaxed = true)
        val cloudWatchClient = mockk<CloudWatchAsyncClient>(relaxed = true)
        val imdsClient = mockk<Ec2MetadataAsyncClient>(relaxed = true)
        val s3ControlClient = mockk<S3ControlAsyncClient>(relaxed = true)

        requireNotNull(StsKtorPluginConfig().apply { stsAsyncClient = stsClient }.toRuntime())
            .registerApplicationResources(registry)
        requireNotNull(SnsKtorPluginConfig().apply { snsAsyncClient = snsClient }.toRuntime())
            .registerApplicationResources(registry)
        requireNotNull(SesKtorPluginConfig().apply { sesV2AsyncClient = sesClient }.toRuntime())
            .registerApplicationResources(registry)
        requireNotNull(EventBridgeKtorPluginConfig().apply { eventBridgeAsyncClient = eventBridgeClient }.toRuntime())
            .registerApplicationResources(registry)
        requireNotNull(KinesisKtorPluginConfig().apply { kinesisAsyncClient = kinesisClient }.toRuntime())
            .registerApplicationResources(registry)
        requireNotNull(S3VectorsKtorPluginConfig().apply { s3VectorsAsyncClient = s3VectorsClient }.toRuntime())
            .registerApplicationResources(registry)
        requireNotNull(CloudWatchKtorPluginConfig().apply { cloudWatchAsyncClient = cloudWatchClient }.toRuntime())
            .registerApplicationResources(registry)
        requireNotNull(ImdsKtorPluginConfig().apply { ec2MetadataAsyncClient = imdsClient }.toRuntime())
            .registerApplicationResources(registry)
        requireNotNull(S3AccessGrantsKtorPluginConfig().apply { s3ControlAsyncClient = s3ControlClient }.toRuntime())
            .registerApplicationResources(registry)

        registry.close()

        verify(exactly = 0) { stsClient.close() }
        verify(exactly = 0) { snsClient.close() }
        verify(exactly = 0) { sesClient.close() }
        verify(exactly = 0) { eventBridgeClient.close() }
        verify(exactly = 0) { kinesisClient.close() }
        verify(exactly = 0) { s3VectorsClient.close() }
        verify(exactly = 0) { cloudWatchClient.close() }
        verify(exactly = 0) { imdsClient.close() }
        verify(exactly = 0) { s3ControlClient.close() }
    }
}
