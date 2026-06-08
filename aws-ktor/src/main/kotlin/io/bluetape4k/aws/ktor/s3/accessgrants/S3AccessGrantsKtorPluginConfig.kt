package io.bluetape4k.aws.ktor.s3.accessgrants

import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorS3ControlAsyncClientCustomizer
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3control.S3ControlAsyncClient
import java.net.URI

/**
 * Configuration for [S3AccessGrantsKtorPlugin].
 *
 * ## Contract
 *
 * Installing the plugin registers operations only. Access Grants calls happen
 * only when application code invokes [S3AccessGrantsKtorOperations].
 */
class S3AccessGrantsKtorPluginConfig {

    /** Enables Ktor S3 Access Grants runtime registration. */
    var enabled: Boolean = true

    /** Optional application-owned AWS SDK v2 S3 Control async client. */
    var s3ControlAsyncClient: S3ControlAsyncClient? = null

    /** Optional application-owned operations facade. */
    var s3AccessGrantsOperations: S3AccessGrantsKtorOperations? = null

    /** Optional S3 Control region used when the plugin creates the client. */
    var region: String? = null

    /** Optional S3 Control endpoint override used when the plugin creates the client. */
    var endpointOverride: URI? = null

    /** Optional credentials provider used when the plugin creates the client. */
    var credentialsProvider: AwsCredentialsProvider? = null

    private val clientCustomizers = mutableListOf<AwsKtorS3ControlAsyncClientCustomizer>()

    /**
     * Adds S3 Control async client builder customization for plugin-created clients.
     */
    fun s3ControlAsyncClient(customizer: AwsKtorS3ControlAsyncClientCustomizer) {
        clientCustomizers += customizer
    }

    internal fun toRuntime(defaults: AwsKtorDefaults = AwsKtorDefaults()): S3AccessGrantsKtorRuntime? {
        if (!enabled) {
            return null
        }

        s3AccessGrantsOperations?.let { return S3AccessGrantsKtorRuntime(it) }

        val injectedClient = s3ControlAsyncClient
        val client = injectedClient ?: createS3ControlAsyncClient(defaults)
        val operations = S3AccessGrantsKtorTemplate(client)

        return S3AccessGrantsKtorRuntime(
            operations = operations,
            ownedClient = if (injectedClient == null) client else null,
        )
    }

    private fun createS3ControlAsyncClient(defaults: AwsKtorDefaults): S3ControlAsyncClient {
        val effectiveRegion = region?.takeIf { it.isNotBlank() } ?: defaults.region?.takeIf { it.isNotBlank() }
        val effectiveEndpoint = endpointOverride ?: defaults.javaEndpointOverride
        require(effectiveEndpoint == null || !effectiveRegion.isNullOrBlank()) {
            "region must be configured when endpointOverride is configured."
        }

        val builder = S3ControlAsyncClient.builder()
        effectiveRegion?.let { builder.region(Region.of(it)) }
        (credentialsProvider ?: defaults.javaCredentialsProvider)?.let(builder::credentialsProvider)
        effectiveEndpoint?.let(builder::endpointOverride)
        defaults.s3ControlAsyncClientCustomizers.forEach { it.customize(builder) }
        clientCustomizers.forEach { it.customize(builder) }

        return builder.build()
    }
}
