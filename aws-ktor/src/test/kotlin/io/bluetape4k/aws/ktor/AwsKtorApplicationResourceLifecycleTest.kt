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
import io.bluetape4k.aws.ktor.s3.accessgrants.S3AccessGrantsKtorPluginConfig
import io.bluetape4k.aws.ktor.s3.accessgrants.S3AccessGrantsKtorRuntime
import io.bluetape4k.aws.ktor.s3vectors.S3VectorsKtorPluginConfig
import io.bluetape4k.aws.ktor.s3vectors.S3VectorsKtorRuntime
import io.bluetape4k.aws.ktor.ses.SesKtorPluginConfig
import io.bluetape4k.aws.ktor.ses.SesKtorRuntime
import io.bluetape4k.aws.ktor.sns.SnsKtorPluginConfig
import io.bluetape4k.aws.ktor.sns.SnsKtorRuntime
import io.bluetape4k.aws.ktor.sts.StsKtorPluginConfig
import io.bluetape4k.aws.ktor.sts.StsKtorRuntime
import io.bluetape4k.ktor.core.ApplicationResourceRegistry
import io.bluetape4k.ktor.core.installApplicationResourceLifecycle
import io.bluetape4k.logging.KLogging
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import io.mockk.every
import io.mockk.verify
import aws.sdk.kotlin.services.cloudwatch.CloudWatchClient
import aws.sdk.kotlin.services.eventbridge.EventBridgeClient
import aws.sdk.kotlin.services.s3control.S3ControlClient
import aws.sdk.kotlin.services.s3vectors.S3VectorsClient
import aws.sdk.kotlin.services.sesv2.SesV2Client
import aws.sdk.kotlin.services.sns.SnsClient
import aws.sdk.kotlin.services.sts.StsClient
import aws.smithy.kotlin.runtime.http.engine.CloseableHttpClientEngine
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import java.util.concurrent.atomic.AtomicInteger

class AwsKtorApplicationResourceLifecycleTest {

    companion object: KLogging()

    @Test
    fun `registered STS client remains open during ApplicationStopping`() {
        val closeCount = AtomicInteger()
        val client = mockk<StsClient>(relaxed = true)
        every { client.close() } answers { closeCount.incrementAndGet() }
        var closeCountAtStopping = -1

        testApplication {
            application {
                val registry = installApplicationResourceLifecycle()
                StsKtorRuntime(mockk(relaxed = true), client).registerApplicationResources(registry)
                monitor.subscribe(ApplicationStopping) {
                    closeCountAtStopping = closeCount.get()
                }
            }

            startApplication()
        }

        closeCountAtStopping shouldBeEqualTo 0
        closeCount.get() shouldBeEqualTo 1
    }

    @Test
    fun `registry closes all simple plugin owned clients once`() {
        val registry = ApplicationResourceRegistry()
        val stsClient = mockk<StsClient>(relaxed = true)
        val snsClient = mockk<SnsClient>(relaxed = true)
        val sesClient = mockk<SesV2Client>(relaxed = true)
        val eventBridgeClient = mockk<EventBridgeClient>(relaxed = true)
        val kinesisClient = mockk<KinesisAsyncClient>(relaxed = true)
        val s3VectorsClient = mockk<S3VectorsClient>(relaxed = true)
        val cloudWatchClient = mockk<CloudWatchClient>(relaxed = true)
        val imdsEngine = mockk<CloseableHttpClientEngine>(relaxed = true)
        val s3ControlClient = mockk<S3ControlClient>(relaxed = true)

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
        ImdsKtorRuntime(mockk(relaxed = true), ownedEngine = imdsEngine).also {
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
        verify(exactly = 1) { imdsEngine.close() }
        verify(exactly = 1) { s3ControlClient.close() }
    }

    @Test
    fun `caller owned clients are not registered for any simple plugin`() {
        val registry = ApplicationResourceRegistry()
        val stsClient = mockk<StsClient>(relaxed = true)
        val snsClient = mockk<SnsClient>(relaxed = true)
        val sesClient = mockk<SesV2Client>(relaxed = true)
        val eventBridgeClient = mockk<EventBridgeClient>(relaxed = true)
        val kinesisClient = mockk<KinesisAsyncClient>(relaxed = true)
        val s3VectorsClient = mockk<S3VectorsClient>(relaxed = true)
        val cloudWatchClient = mockk<CloudWatchClient>(relaxed = true)
        val s3ControlClient = mockk<S3ControlClient>(relaxed = true)

        requireNotNull(StsKtorPluginConfig().apply { this.stsClient = stsClient }.toRuntime())
            .registerApplicationResources(registry)
        requireNotNull(SnsKtorPluginConfig().apply { this.snsClient = snsClient }.toRuntime())
            .registerApplicationResources(registry)
        requireNotNull(SesKtorPluginConfig().apply { this.sesV2Client = sesClient }.toRuntime())
            .registerApplicationResources(registry)
        requireNotNull(EventBridgeKtorPluginConfig().apply { this.eventBridgeClient = eventBridgeClient }.toRuntime())
            .registerApplicationResources(registry)
        requireNotNull(KinesisKtorPluginConfig().apply { kinesisAsyncClient = kinesisClient }.toRuntime())
            .registerApplicationResources(registry)
        requireNotNull(S3VectorsKtorPluginConfig().apply { this.s3VectorsClient = s3VectorsClient }.toRuntime())
            .registerApplicationResources(registry)
        requireNotNull(CloudWatchKtorPluginConfig().apply { this.cloudWatchClient = cloudWatchClient }.toRuntime())
            .registerApplicationResources(registry)
        requireNotNull(ImdsKtorPluginConfig().apply { imdsOperations = mockk(relaxed = true) }.toRuntime())
            .registerApplicationResources(registry)
        requireNotNull(S3AccessGrantsKtorPluginConfig().apply { this.s3ControlClient = s3ControlClient }.toRuntime())
            .registerApplicationResources(registry)

        registry.close()

        verify(exactly = 0) { stsClient.close() }
        verify(exactly = 0) { snsClient.close() }
        verify(exactly = 0) { sesClient.close() }
        verify(exactly = 0) { eventBridgeClient.close() }
        verify(exactly = 0) { kinesisClient.close() }
        verify(exactly = 0) { s3VectorsClient.close() }
        verify(exactly = 0) { cloudWatchClient.close() }
        verify(exactly = 0) { s3ControlClient.close() }
    }
}
