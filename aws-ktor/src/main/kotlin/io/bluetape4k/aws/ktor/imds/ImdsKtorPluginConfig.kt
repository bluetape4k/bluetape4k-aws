package io.bluetape4k.aws.ktor.imds

import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireZeroOrPositiveNumber
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.imds.Ec2MetadataAsyncClient
import software.amazon.awssdk.imds.Ec2MetadataRetryPolicy
import software.amazon.awssdk.imds.EndpointMode
import java.net.URI
import java.time.Duration

/**
 * Configuration for the Ktor IMDS plugin.
 *
 * ## Contract
 *
 * Installing the plugin never calls IMDS. Metadata requests happen only through
 * [ImdsKtorOperations] methods and are bounded by [requestTimeout].
 */
class ImdsKtorPluginConfig {

    /** Enables Ktor IMDS runtime registration. */
    var enabled: Boolean = true

    /** Optional application-owned AWS SDK v2 IMDS async client. */
    var ec2MetadataAsyncClient: Ec2MetadataAsyncClient? = null

    /** Optional application-owned operations facade. */
    var imdsOperations: ImdsKtorOperations? = null

    /** Optional metadata endpoint override. Prefer this only for tests or custom EC2 metadata routing. */
    var endpoint: URI? = null

    /** IMDS endpoint mode used when [endpoint] is not set. */
    var endpointMode: EndpointMode? = EndpointMode.IPV4

    /** IMDSv2 token TTL. */
    var tokenTtl: Duration = Duration.ofHours(6)

    /** Timeout applied to each metadata operation. */
    var requestTimeout: Duration = Duration.ofSeconds(1)

    /** Number of SDK-level IMDS retries. Zero disables SDK retries. */
    var retries: Int = 0

    /** Optional async HTTP client for plugin-created IMDS clients. */
    var httpClient: SdkAsyncHttpClient? = null

    private val clientCustomizers = mutableListOf<ImdsKtorClientCustomizer>()

    /**
     * Adds IMDS async client builder customization for plugin-created clients.
     */
    fun client(customizer: ImdsKtorClientCustomizer) {
        clientCustomizers += customizer
    }

    internal fun toRuntime(): ImdsKtorRuntime? {
        if (!enabled) {
            return null
        }

        imdsOperations?.let { return ImdsKtorRuntime(it) }

        validate()

        val injectedClient = ec2MetadataAsyncClient
        val client = injectedClient ?: createEc2MetadataAsyncClient()
        val operations = ImdsKtorTemplate(client, requestTimeout)

        return ImdsKtorRuntime(
            operations = operations,
            ownedClient = if (injectedClient == null) client else null,
        )
    }

    private fun validate() {
        tokenTtl.requireGt(Duration.ZERO, "tokenTtl")
        requestTimeout.requireGt(Duration.ZERO, "requestTimeout")
        retries.requireZeroOrPositiveNumber("retries")
    }

    private fun createEc2MetadataAsyncClient(): Ec2MetadataAsyncClient =
        Ec2MetadataAsyncClient.builder()
            .tokenTtl(tokenTtl)
            .retryPolicy(resolveRetryPolicy())
            .httpClient(httpClient ?: SdkAsyncHttpClientProvider.defaultHttpClient)
            .apply {
                endpoint?.let { endpoint(it) } ?: endpointMode?.let { endpointMode(it) }
                clientCustomizers.forEach { it.customize(this) }
            }
            .build()

    private fun resolveRetryPolicy(): Ec2MetadataRetryPolicy =
        if (retries == 0) {
            Ec2MetadataRetryPolicy.none()
        } else {
            Ec2MetadataRetryPolicy.builder()
                .numRetries(retries)
                .build()
        }
}

/**
 * Customizes plugin-created AWS SDK v2 IMDS async client builders.
 */
fun interface ImdsKtorClientCustomizer {
    fun customize(builder: Ec2MetadataAsyncClient.Builder)
}
