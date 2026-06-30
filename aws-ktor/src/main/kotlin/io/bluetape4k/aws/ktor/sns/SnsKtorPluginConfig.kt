package io.bluetape4k.aws.ktor.sns

import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorSnsAsyncClientCustomizer
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sns.SnsAsyncClient
import java.io.Serializable
import java.net.URI

/**
 * Configuration for [SnsKtorPlugin].
 *
 * ## Contract
 *
 * Installing the plugin registers operations and an SNS HTTP parser only. It
 * does not create topics, publish messages, or confirm subscriptions until
 * application code invokes [SnsKtorOperations].
 */
class SnsKtorPluginConfig {

    /** Enables Ktor SNS runtime registration. */
    var enabled: Boolean = true

    /** Optional application-owned AWS SDK v2 SNS async client. */
    var snsAsyncClient: SnsAsyncClient? = null

    /** Optional application-owned operations facade. */
    var snsOperations: SnsKtorOperations? = null

    /** Optional application-owned SNS HTTP message parser. */
    var snsHttpMessageParser: SnsHttpMessageParser? = null

    /** Optional SNS region used when the plugin creates the client. */
    var region: String? = null

    /** Optional SNS endpoint override used when the plugin creates the client. */
    var endpointOverride: URI? = null

    /** Optional credentials provider used when the plugin creates the client. */
    var credentialsProvider: AwsCredentialsProvider? = null

    /** Topic definitions used by [SnsKtorOperations.createConfiguredTopic]. */
    var topics: Map<String, SnsKtorTopic> = emptyMap()

    private val clientCustomizers = mutableListOf<AwsKtorSnsAsyncClientCustomizer>()

    /**
     * Adds SNS async client builder customization for plugin-created clients.
     */
    fun snsAsyncClient(customizer: AwsKtorSnsAsyncClientCustomizer) {
        clientCustomizers += customizer
    }

    internal fun toRuntime(defaults: AwsKtorDefaults = AwsKtorDefaults()): SnsKtorRuntime? {
        if (!enabled) {
            return null
        }

        val parser = snsHttpMessageParser ?: SnsHttpMessageParser.default()
        snsOperations?.let { return SnsKtorRuntime(operations = it, parser = parser) }

        val injectedClient = snsAsyncClient
        val client = injectedClient ?: createSnsAsyncClient(defaults)
        val operations = SnsKtorTemplate(client, topics)

        return SnsKtorRuntime(
            operations = operations,
            parser = parser,
            ownedClient = if (injectedClient == null) client else null,
        )
    }

    private fun createSnsAsyncClient(defaults: AwsKtorDefaults): SnsAsyncClient {
        val effectiveRegion = region?.takeIf { it.isNotBlank() } ?: defaults.region?.takeIf { it.isNotBlank() }
        val effectiveEndpoint = endpointOverride ?: defaults.javaEndpointOverride
        require(effectiveEndpoint == null || !effectiveRegion.isNullOrBlank()) {
            "region must be configured when endpointOverride is configured."
        }

        val builder = SnsAsyncClient.builder()
        effectiveRegion?.let { builder.region(Region.of(it)) }
        (credentialsProvider ?: defaults.javaCredentialsProvider)?.let(builder::credentialsProvider)
        effectiveEndpoint?.let(builder::endpointOverride)
        defaults.snsAsyncClientCustomizers.forEach { it.customize(builder) }
        clientCustomizers.forEach { it.customize(builder) }

        return builder.build()
    }
}

/**
 * Topic properties used by configuration-driven SNS topic creation.
 */
data class SnsKtorTopic(
    val fifo: Boolean = false,
    val contentBasedDeduplication: Boolean = true,
    val fifoThroughputScope: SnsFifoThroughputScope? = null,
    val attributes: Map<String, String> = emptyMap(),
): Serializable {
    companion object {
        private const val serialVersionUID: Long = -5060828396544333540L
    }
}
