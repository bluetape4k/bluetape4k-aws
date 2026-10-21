package io.bluetape4k.aws.ktor

import aws.sdk.kotlin.services.cloudwatch.endpoints.CloudWatchEndpointProvider
import aws.sdk.kotlin.services.cloudwatchlogs.endpoints.CloudWatchLogsEndpointProvider
import aws.sdk.kotlin.services.eventbridge.endpoints.EventBridgeEndpointProvider
import aws.sdk.kotlin.services.kinesis.endpoints.KinesisEndpointProvider
import aws.sdk.kotlin.services.s3control.endpoints.S3ControlEndpointProvider
import aws.sdk.kotlin.services.s3vectors.endpoints.S3VectorsEndpointProvider
import aws.sdk.kotlin.services.sesv2.endpoints.SesV2EndpointProvider
import aws.sdk.kotlin.services.sns.endpoints.SnsEndpointProvider
import aws.sdk.kotlin.services.sqs.endpoints.SqsEndpointProvider
import aws.sdk.kotlin.services.sts.endpoints.StsEndpointProvider
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.client.endpoints.Endpoint
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.aws.ktor.cloudwatch.CloudWatchKtorPluginConfig
import io.bluetape4k.aws.ktor.cloudwatch.CloudWatchLogsKtorPluginConfig
import io.bluetape4k.aws.ktor.eventbridge.EventBridgeKtorPluginConfig
import io.bluetape4k.aws.ktor.kinesis.KinesisKtorPluginConfig
import io.bluetape4k.aws.ktor.s3.accessgrants.S3AccessGrantsKtorPluginConfig
import io.bluetape4k.aws.ktor.s3vectors.S3VectorsKtorPluginConfig
import io.bluetape4k.aws.ktor.ses.SesKtorPluginConfig
import io.bluetape4k.aws.ktor.sns.SnsKtorPluginConfig
import io.bluetape4k.aws.ktor.sqs.SqsConsumerPluginConfig
import io.bluetape4k.aws.ktor.sts.StsKtorPluginConfig
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.Test

class AwsKtorEndpointPolicyWiringTest {

    @Test
    fun `CloudWatch rejects service endpoint provider before resolving credentials`() =
        assertRejectedBeforeCredentialsResolved { credentialsProvider ->
            CloudWatchKtorPluginConfig().apply {
                cloudWatchClient(
                    AwsKtorCloudWatchClientCustomizer { builder ->
                        builder.endpointProvider = CloudWatchEndpointProvider { endpoint() }
                    },
                )
            }.toRuntime(defaults(credentialsProvider))
        }

    @Test
    fun `CloudWatch Logs rejects service endpoint provider before resolving credentials`() =
        assertRejectedBeforeCredentialsResolved { credentialsProvider ->
            CloudWatchLogsKtorPluginConfig().apply {
                cloudWatchLogsClient(
                    AwsKtorCloudWatchLogsClientCustomizer { builder ->
                        builder.endpointProvider = CloudWatchLogsEndpointProvider { endpoint() }
                    },
                )
            }.toRuntime(defaults(credentialsProvider))
        }

    @Test
    fun `EventBridge rejects service endpoint provider before resolving credentials`() =
        assertRejectedBeforeCredentialsResolved { credentialsProvider ->
            EventBridgeKtorPluginConfig().apply {
                eventBridgeClient(
                    AwsKtorEventBridgeClientCustomizer { builder ->
                        builder.endpointProvider = EventBridgeEndpointProvider { endpoint() }
                    },
                )
            }.toRuntime(defaults(credentialsProvider))
        }

    @Test
    fun `Kinesis rejects service endpoint provider before resolving credentials`() =
        assertRejectedBeforeCredentialsResolved { credentialsProvider ->
            KinesisKtorPluginConfig().apply {
                kinesisClient(
                    AwsKtorKinesisClientCustomizer { builder ->
                        builder.endpointProvider = KinesisEndpointProvider { endpoint() }
                    },
                )
            }.toRuntime(defaults(credentialsProvider))
        }

    @Test
    fun `S3 Control rejects service endpoint provider before resolving credentials`() =
        assertRejectedBeforeCredentialsResolved { credentialsProvider ->
            S3AccessGrantsKtorPluginConfig().apply {
                s3ControlClient(
                    AwsKtorS3ControlClientCustomizer { builder ->
                        builder.endpointProvider = S3ControlEndpointProvider { endpoint() }
                    },
                )
            }.toRuntime(defaults(credentialsProvider))
        }

    @Test
    fun `S3 Vectors rejects service endpoint provider before resolving credentials`() =
        assertRejectedBeforeCredentialsResolved { credentialsProvider ->
            S3VectorsKtorPluginConfig().apply {
                s3VectorsClient(
                    AwsKtorS3VectorsClientCustomizer { builder ->
                        builder.endpointProvider = S3VectorsEndpointProvider { endpoint() }
                    },
                )
            }.toRuntime(defaults(credentialsProvider))
        }

    @Test
    fun `SES v2 rejects service endpoint provider before resolving credentials`() =
        assertRejectedBeforeCredentialsResolved { credentialsProvider ->
            SesKtorPluginConfig().apply {
                sesV2Client(
                    AwsKtorSesV2ClientCustomizer { builder ->
                        builder.endpointProvider = SesV2EndpointProvider { endpoint() }
                    },
                )
            }.toRuntime(defaults(credentialsProvider))
        }

    @Test
    fun `SNS rejects service endpoint provider before resolving credentials`() =
        assertRejectedBeforeCredentialsResolved { credentialsProvider ->
            SnsKtorPluginConfig().apply {
                snsClient(
                    AwsKtorSnsClientCustomizer { builder ->
                        builder.endpointProvider = SnsEndpointProvider { endpoint() }
                    },
                )
            }.toRuntime(defaults(credentialsProvider))
        }

    @Test
    fun `SQS rejects service endpoint provider before resolving credentials`() =
        assertRejectedBeforeCredentialsResolved { credentialsProvider ->
            SqsConsumerPluginConfig().apply {
                onMessage<String> { }
                sqsClient(
                    AwsKtorSqsClientCustomizer { builder ->
                        builder.endpointProvider = SqsEndpointProvider { endpoint() }
                    },
                )
            }.toRuntimeConfig(defaults(credentialsProvider))
        }

    @Test
    fun `STS rejects service endpoint provider before resolving credentials`() =
        assertRejectedBeforeCredentialsResolved { credentialsProvider ->
            StsKtorPluginConfig().apply {
                stsClient(
                    AwsKtorStsClientCustomizer { builder ->
                        builder.endpointProvider = StsEndpointProvider { endpoint() }
                    },
                )
            }.toRuntime(defaults(credentialsProvider))
        }

    private fun assertRejectedBeforeCredentialsResolved(configure: (CredentialsProvider) -> Unit) {
        val credentialsProvider = mockk<CredentialsProvider>(relaxed = true)

        assertFailsWith<IllegalArgumentException> {
            configure(credentialsProvider)
        }

        coVerify(exactly = 0) { credentialsProvider.resolve() }
    }

    private fun defaults(credentialsProvider: CredentialsProvider) =
        AwsKtorDefaults(
            region = "ap-northeast-2",
            kotlinCredentialsProvider = credentialsProvider,
        )

    private fun endpoint() = Endpoint(Url.parse("https://service.example.com"))
}
