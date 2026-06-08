package io.bluetape4k.aws.ktor.cloudwatch

import io.bluetape4k.aws.ktor.AwsKtorCloudWatchAsyncClientCustomizer
import io.bluetape4k.aws.ktor.AwsKtorDefaults
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import java.net.URI

/**
 * Configuration for [CloudWatchKtorPlugin].
 *
 * ## Contract
 *
 * Installing the plugin registers operations only. It does not publish metrics
 * until application code invokes [CloudWatchKtorOperations].
 */
class CloudWatchKtorPluginConfig {

    /** Enables Ktor CloudWatch runtime registration. */
    var enabled: Boolean = true

    /** Optional application-owned AWS SDK v2 CloudWatch async client. */
    var cloudWatchAsyncClient: CloudWatchAsyncClient? = null

    /** Optional application-owned operations facade. */
    var cloudWatchOperations: CloudWatchKtorOperations? = null

    /** Optional CloudWatch region used when the plugin creates the client. */
    var region: String? = null

    /** Optional CloudWatch endpoint override used when the plugin creates the client. */
    var endpointOverride: URI? = null

    /** Optional credentials provider used when the plugin creates the client. */
    var credentialsProvider: AwsCredentialsProvider? = null

    /** Default CloudWatch namespace for operations that omit namespace. */
    var namespace: String? = null

    /** CloudWatch PutMetricData batch size. AWS allows 1..1000. */
    var batchSize: Int = CLOUDWATCH_MAX_BATCH_SIZE

    private val clientCustomizers = mutableListOf<AwsKtorCloudWatchAsyncClientCustomizer>()

    /**
     * Adds CloudWatch async client builder customization for plugin-created clients.
     */
    fun cloudWatchAsyncClient(customizer: AwsKtorCloudWatchAsyncClientCustomizer) {
        clientCustomizers += customizer
    }

    internal fun toRuntime(defaults: AwsKtorDefaults = AwsKtorDefaults()): CloudWatchKtorRuntime? {
        if (!enabled) {
            return null
        }

        cloudWatchOperations?.let { return CloudWatchKtorRuntime(it) }

        val injectedClient = cloudWatchAsyncClient
        val client = injectedClient ?: createCloudWatchAsyncClient(defaults)
        val operations = CloudWatchKtorTemplate(client, namespace, batchSize)

        return CloudWatchKtorRuntime(
            operations = operations,
            ownedClient = if (injectedClient == null) client else null,
        )
    }

    private fun createCloudWatchAsyncClient(defaults: AwsKtorDefaults): CloudWatchAsyncClient {
        val effectiveRegion = region?.takeIf { it.isNotBlank() } ?: defaults.region?.takeIf { it.isNotBlank() }
        val effectiveEndpoint = endpointOverride ?: defaults.javaEndpointOverride
        require(effectiveEndpoint == null || !effectiveRegion.isNullOrBlank()) {
            "region must be configured when endpointOverride is configured."
        }

        val builder = CloudWatchAsyncClient.builder()
        effectiveRegion?.let { builder.region(Region.of(it)) }
        (credentialsProvider ?: defaults.javaCredentialsProvider)?.let(builder::credentialsProvider)
        effectiveEndpoint?.let(builder::endpointOverride)
        defaults.cloudWatchAsyncClientCustomizers.forEach { it.customize(builder) }
        clientCustomizers.forEach { it.customize(builder) }

        return builder.build()
    }
}
