package io.bluetape4k.aws.ktor.ses

import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorSesV2AsyncClientCustomizer
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sesv2.SesV2AsyncClient
import java.net.URI

/**
 * Configuration for [SesKtorPlugin].
 *
 * ## Contract
 *
 * Installing the plugin registers operations only. It does not send email
 * until application code invokes [SesKtorOperations].
 */
class SesKtorPluginConfig {

    /** Enables Ktor SES runtime registration. */
    var enabled: Boolean = true

    /** Optional application-owned AWS SDK v2 SES v2 async client. */
    var sesV2AsyncClient: SesV2AsyncClient? = null

    /** Optional application-owned operations facade. */
    var sesOperations: SesKtorOperations? = null

    /** Optional SES region used when the plugin creates the client. */
    var region: String? = null

    /** Optional SES endpoint override used when the plugin creates the client. */
    var endpointOverride: URI? = null

    /** Optional credentials provider used when the plugin creates the client. */
    var credentialsProvider: AwsCredentialsProvider? = null

    /** Default sender used when a request omits `from`. */
    var defaultFrom: String? = null

    /** Default SES configuration set used when a request omits one. */
    var configurationSetName: String? = null

    private val clientCustomizers = mutableListOf<AwsKtorSesV2AsyncClientCustomizer>()

    /**
     * Adds SES v2 async client builder customization for plugin-created clients.
     */
    fun sesV2AsyncClient(customizer: AwsKtorSesV2AsyncClientCustomizer) {
        clientCustomizers += customizer
    }

    internal fun toRuntime(defaults: AwsKtorDefaults = AwsKtorDefaults()): SesKtorRuntime? {
        if (!enabled) {
            return null
        }

        sesOperations?.let { return SesKtorRuntime(it) }

        defaultFrom?.requireEmailHeaderValue("defaultFrom")
        configurationSetName?.let { require(it.isNotBlank()) { "configurationSetName must not be blank." } }

        val injectedClient = sesV2AsyncClient
        val client = injectedClient ?: createSesV2AsyncClient(defaults)
        val operations = SesKtorTemplate(
            sesAsyncClient = client,
            defaultFrom = defaultFrom,
            configurationSetName = configurationSetName,
        )

        return SesKtorRuntime(
            operations = operations,
            ownedClient = if (injectedClient == null) client else null,
        )
    }

    private fun createSesV2AsyncClient(defaults: AwsKtorDefaults): SesV2AsyncClient {
        val effectiveRegion = region?.takeIf { it.isNotBlank() } ?: defaults.region?.takeIf { it.isNotBlank() }
        val effectiveEndpoint = endpointOverride ?: defaults.javaEndpointOverride
        require(effectiveEndpoint == null || !effectiveRegion.isNullOrBlank()) {
            "region must be configured when endpointOverride is configured."
        }

        val builder = SesV2AsyncClient.builder()
        effectiveRegion?.let { builder.region(Region.of(it)) }
        (credentialsProvider ?: defaults.javaCredentialsProvider)?.let(builder::credentialsProvider)
        effectiveEndpoint?.let(builder::endpointOverride)
        defaults.sesV2AsyncClientCustomizers.forEach { it.customize(builder) }
        clientCustomizers.forEach { it.customize(builder) }

        return builder.build()
    }
}
