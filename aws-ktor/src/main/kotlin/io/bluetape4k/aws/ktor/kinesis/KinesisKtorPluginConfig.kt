package io.bluetape4k.aws.ktor.kinesis

import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorKinesisAsyncClientCustomizer
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import java.net.URI

/**
 * Configuration for [KinesisKtorPlugin].
 *
 * ## Contract
 *
 * Installing the plugin registers operations only. Streams are created,
 * records are published, and consumer Flows are collected only when
 * application code invokes [KinesisKtorOperations].
 */
class KinesisKtorPluginConfig {

    /** Enables Ktor Kinesis runtime registration. */
    var enabled: Boolean = true

    /** Optional application-owned AWS SDK v2 Kinesis async client. */
    var kinesisAsyncClient: KinesisAsyncClient? = null

    /** Optional application-owned operations facade. */
    var kinesisOperations: KinesisKtorOperations? = null

    /** Optional Kinesis region used when the plugin creates the client. */
    var region: String? = null

    /** Optional Kinesis endpoint override used when the plugin creates the client. */
    var endpointOverride: URI? = null

    /** Optional credentials provider used when the plugin creates the client. */
    var credentialsProvider: AwsCredentialsProvider? = null

    /** Stream definitions used by [KinesisKtorOperations.createConfiguredStream]. */
    var streams: Map<String, KinesisKtorStream> = emptyMap()

    private val clientCustomizers = mutableListOf<AwsKtorKinesisAsyncClientCustomizer>()

    /**
     * Adds Kinesis async client builder customization for plugin-created clients.
     */
    fun kinesisAsyncClient(customizer: AwsKtorKinesisAsyncClientCustomizer) {
        clientCustomizers += customizer
    }

    internal fun toRuntime(defaults: AwsKtorDefaults = AwsKtorDefaults()): KinesisKtorRuntime? {
        if (!enabled) {
            return null
        }

        kinesisOperations?.let { return KinesisKtorRuntime(it) }

        val injectedClient = kinesisAsyncClient
        val client = injectedClient ?: createKinesisAsyncClient(defaults)
        val operations = KinesisKtorTemplate(client, streams)

        return KinesisKtorRuntime(
            operations = operations,
            ownedClient = if (injectedClient == null) client else null,
        )
    }

    private fun createKinesisAsyncClient(defaults: AwsKtorDefaults): KinesisAsyncClient {
        val effectiveRegion = region?.takeIf { it.isNotBlank() } ?: defaults.region?.takeIf { it.isNotBlank() }
        val effectiveEndpoint = endpointOverride ?: defaults.javaEndpointOverride
        require(effectiveEndpoint == null || !effectiveRegion.isNullOrBlank()) {
            "region must be configured when endpointOverride is configured."
        }

        val builder = KinesisAsyncClient.builder()
        effectiveRegion?.let { builder.region(Region.of(it)) }
        (credentialsProvider ?: defaults.javaCredentialsProvider)?.let(builder::credentialsProvider)
        effectiveEndpoint?.let(builder::endpointOverride)
        defaults.kinesisAsyncClientCustomizers.forEach { it.customize(builder) }
        clientCustomizers.forEach { it.customize(builder) }

        return builder.build()
    }
}
