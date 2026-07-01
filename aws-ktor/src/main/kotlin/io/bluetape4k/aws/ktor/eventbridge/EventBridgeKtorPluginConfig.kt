package io.bluetape4k.aws.ktor.eventbridge

import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorEventBridgeAsyncClientCustomizer
import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.eventbridge.EventBridgeAsyncClient
import java.net.URI

/**
 * Configuration for [EventBridgeKtorPlugin].
 *
 * ## Contract
 *
 * Installing the plugin registers operations only. Events are sent only when
 * application code invokes [EventBridgeKtorOperations].
 */
class EventBridgeKtorPluginConfig {

    /** Enables Ktor EventBridge runtime registration. */
    var enabled: Boolean = true

    /** Optional application-owned AWS SDK v2 EventBridge async client. */
    var eventBridgeAsyncClient: EventBridgeAsyncClient? = null

    /** Optional application-owned operations facade. */
    var eventBridgeOperations: EventBridgeKtorOperations? = null

    /** Optional EventBridge region used when the plugin creates the client. */
    var region: String? = null

    /** Optional EventBridge endpoint override used when the plugin creates the client. */
    var endpointOverride: URI? = null

    /** Optional credentials provider used when the plugin creates the client. */
    var credentialsProvider: AwsCredentialsProvider? = null

    /** Default event bus for rule, target, and list operations that omit one. */
    var defaultEventBusName: String? = null

    private val clientCustomizers = mutableListOf<AwsKtorEventBridgeAsyncClientCustomizer>()

    /**
     * Adds EventBridge async client builder customization for plugin-created clients.
     */
    fun eventBridgeAsyncClient(customizer: AwsKtorEventBridgeAsyncClientCustomizer) {
        clientCustomizers += customizer
    }

    internal fun toRuntime(defaults: AwsKtorDefaults = AwsKtorDefaults()): EventBridgeKtorRuntime? {
        if (!enabled) {
            return null
        }

        eventBridgeOperations?.let { return EventBridgeKtorRuntime(it) }

        defaultEventBusName?.requireNotBlank("defaultEventBusName")

        val injectedClient = eventBridgeAsyncClient
        val client = injectedClient ?: createEventBridgeAsyncClient(defaults)
        val operations = EventBridgeKtorTemplate(
            eventBridgeAsyncClient = client,
            defaultEventBusName = defaultEventBusName,
        )

        return EventBridgeKtorRuntime(
            operations = operations,
            ownedClient = if (injectedClient == null) client else null,
        )
    }

    private fun createEventBridgeAsyncClient(defaults: AwsKtorDefaults): EventBridgeAsyncClient {
        val effectiveRegion = region?.takeIf { it.isNotBlank() } ?: defaults.region?.takeIf { it.isNotBlank() }
        val effectiveEndpoint = endpointOverride ?: defaults.javaEndpointOverride
        require(effectiveEndpoint == null || !effectiveRegion.isNullOrBlank()) {
            "region must be configured when endpointOverride is configured."
        }

        val builder = EventBridgeAsyncClient.builder()
        effectiveRegion?.let { builder.region(Region.of(it)) }
        (credentialsProvider ?: defaults.javaCredentialsProvider)?.let(builder::credentialsProvider)
        effectiveEndpoint?.let(builder::endpointOverride)
        defaults.eventBridgeAsyncClientCustomizers.forEach { it.customize(builder) }
        clientCustomizers.forEach { it.customize(builder) }

        return builder.build()
    }
}
