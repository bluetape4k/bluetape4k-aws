package io.bluetape4k.aws.ktor.s3vectors

import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorS3VectorsAsyncClientCustomizer
import io.bluetape4k.aws.s3vectors.S3VectorsCoroutinesTemplate
import io.bluetape4k.aws.s3vectors.S3VectorsOperations
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3vectors.S3VectorsAsyncClient
import java.net.URI

/**
 * Configuration for [S3VectorsKtorPlugin].
 *
 * ## Contract
 *
 * Installing the plugin registers operations only. S3 Vectors calls happen only
 * when application code invokes [S3VectorsOperations].
 */
class S3VectorsKtorPluginConfig {

    /** Enables Ktor S3 Vectors runtime registration. */
    var enabled: Boolean = true

    /** Optional application-owned AWS SDK v2 S3 Vectors async client. */
    var s3VectorsAsyncClient: S3VectorsAsyncClient? = null

    /** Optional application-owned operations facade. */
    var s3VectorsOperations: S3VectorsOperations? = null

    /** Optional S3 Vectors region used when the plugin creates the client. */
    var region: String? = null

    /** Optional S3 Vectors endpoint override used when the plugin creates the client. */
    var endpointOverride: URI? = null

    /** Optional credentials provider used when the plugin creates the client. */
    var credentialsProvider: AwsCredentialsProvider? = null

    private val clientCustomizers = mutableListOf<AwsKtorS3VectorsAsyncClientCustomizer>()

    /**
     * Adds S3 Vectors async client builder customization for plugin-created clients.
     */
    fun s3VectorsAsyncClient(customizer: AwsKtorS3VectorsAsyncClientCustomizer) {
        clientCustomizers += customizer
    }

    internal fun toRuntime(defaults: AwsKtorDefaults = AwsKtorDefaults()): S3VectorsKtorRuntime? {
        if (!enabled) {
            return null
        }

        s3VectorsOperations?.let { return S3VectorsKtorRuntime(it) }

        val injectedClient = s3VectorsAsyncClient
        val client = injectedClient ?: createS3VectorsAsyncClient(defaults)
        val operations = S3VectorsCoroutinesTemplate(client)

        return S3VectorsKtorRuntime(
            operations = operations,
            ownedClient = if (injectedClient == null) client else null,
        )
    }

    private fun createS3VectorsAsyncClient(defaults: AwsKtorDefaults): S3VectorsAsyncClient {
        val effectiveRegion = region?.takeIf { it.isNotBlank() } ?: defaults.region?.takeIf { it.isNotBlank() }
        val effectiveEndpoint = endpointOverride ?: defaults.javaEndpointOverride
        require(effectiveEndpoint == null || !effectiveRegion.isNullOrBlank()) {
            "region must be configured when endpointOverride is configured."
        }

        val builder = S3VectorsAsyncClient.builder()
        effectiveRegion?.let { builder.region(Region.of(it)) }
        (credentialsProvider ?: defaults.javaCredentialsProvider)?.let(builder::credentialsProvider)
        effectiveEndpoint?.let(builder::endpointOverride)
        defaults.s3VectorsAsyncClientCustomizers.forEach { it.customize(builder) }
        clientCustomizers.forEach { it.customize(builder) }

        return builder.build()
    }
}
