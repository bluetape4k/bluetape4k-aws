package io.bluetape4k.aws.ktor.sts

import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorStsAsyncClientCustomizer
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sts.StsAsyncClient
import java.net.URI

/**
 * Configuration for [StsKtorPlugin].
 *
 * ## Contract
 *
 * Installing the plugin registers operations only. STS identity or session
 * calls happen only when application code invokes [StsKtorOperations].
 */
class StsKtorPluginConfig {

    /** Enables Ktor STS runtime registration. */
    var enabled: Boolean = true

    /** Optional application-owned AWS SDK v2 STS async client. */
    var stsAsyncClient: StsAsyncClient? = null

    /** Optional application-owned operations facade. */
    var stsOperations: StsKtorOperations? = null

    /** Optional STS region used when the plugin creates the client. */
    var region: String? = null

    /** Optional STS endpoint override used when the plugin creates the client. */
    var endpointOverride: URI? = null

    /** Optional credentials provider used when the plugin creates the client. */
    var credentialsProvider: AwsCredentialsProvider? = null

    private val clientCustomizers = mutableListOf<AwsKtorStsAsyncClientCustomizer>()

    /**
     * Adds STS async client builder customization for plugin-created clients.
     */
    fun stsAsyncClient(customizer: AwsKtorStsAsyncClientCustomizer) {
        clientCustomizers += customizer
    }

    internal fun toRuntime(defaults: AwsKtorDefaults = AwsKtorDefaults()): StsKtorRuntime? {
        if (!enabled) {
            return null
        }

        stsOperations?.let { return StsKtorRuntime(it) }

        val injectedClient = stsAsyncClient
        val client = injectedClient ?: createStsAsyncClient(defaults)
        val operations = StsKtorTemplate(client)

        return StsKtorRuntime(
            operations = operations,
            ownedClient = if (injectedClient == null) client else null,
        )
    }

    private fun createStsAsyncClient(defaults: AwsKtorDefaults): StsAsyncClient {
        val effectiveRegion = region?.takeIf { it.isNotBlank() } ?: defaults.region?.takeIf { it.isNotBlank() }
        val effectiveEndpoint = endpointOverride ?: defaults.javaEndpointOverride
        require(effectiveEndpoint == null || !effectiveRegion.isNullOrBlank()) {
            "region must be configured when endpointOverride is configured."
        }

        val builder = StsAsyncClient.builder()
        effectiveRegion?.let { builder.region(Region.of(it)) }
        (credentialsProvider ?: defaults.javaCredentialsProvider)?.let(builder::credentialsProvider)
        effectiveEndpoint?.let(builder::endpointOverride)
        defaults.stsAsyncClientCustomizers.forEach { it.customize(builder) }
        clientCustomizers.forEach { it.customize(builder) }

        return builder.build()
    }
}
