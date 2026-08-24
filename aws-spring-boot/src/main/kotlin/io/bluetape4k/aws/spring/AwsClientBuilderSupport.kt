package io.bluetape4k.aws.spring

import io.bluetape4k.aws.spring.connection.AwsServiceConnectionConfigurationException
import io.bluetape4k.aws.spring.connection.AwsServiceConnectionCredentialsResolver
import io.bluetape4k.aws.spring.connection.AwsServiceConnectionDetails
import org.springframework.beans.factory.ObjectProvider
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.awscore.client.builder.AwsAsyncClientBuilder
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder
import software.amazon.awssdk.regions.Region
import java.io.Serializable
import java.net.URI

internal data class AwsClientDefaults(
    val region: Region?,
    val endpointOverride: URI?,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 8632029964960808683L
    }
}

internal fun AwsProperties.resolveClientDefaults(
    serviceRegion: String?,
    serviceEndpointOverride: URI?,
): AwsClientDefaults {
    val resolvedRegion = serviceRegion ?: region
    val resolvedEndpointOverride = serviceEndpointOverride ?: endpointOverride

    require(resolvedEndpointOverride == null || !resolvedRegion.isNullOrBlank()) {
        "AWS region is required when endpointOverride is configured."
    }

    return AwsClientDefaults(
        region = resolvedRegion?.let { Region.of(it) },
        endpointOverride = resolvedEndpointOverride,
    )
}

internal fun <D: AwsServiceConnectionDetails> resolveServiceClientDefaults(
    connectionDetails: ObjectProvider<D>,
    awsProperties: ObjectProvider<AwsProperties>,
    serviceName: String,
    serviceRegion: String?,
    serviceEndpointOverride: URI?,
): AwsClientDefaults {
    val candidates = connectionDetails.orderedStream().toList()
    if (candidates.size > 1) {
        throw AwsServiceConnectionConfigurationException(
            reason = AwsServiceConnectionConfigurationException.Reason.DUPLICATE_DETAILS,
            serviceNames = setOf(serviceName),
            candidateCount = candidates.size,
        )
    }

    val details = candidates.firstOrNull()
    return if (details != null) {
        AwsClientDefaults(
            region = Region.of(details.region),
            endpointOverride = details.endpoint,
        )
    } else {
        awsProperties.getIfAvailable { AwsProperties() }
            .resolveClientDefaults(serviceRegion, serviceEndpointOverride)
    }
}

internal fun resolveAwsCredentialsProvider(
    provider: ObjectProvider<AwsCredentialsProvider>,
    connectionDetails: ObjectProvider<AwsServiceConnectionDetails>,
): AwsCredentialsProvider =
    provider.getIfAvailable { AwsServiceConnectionCredentialsResolver.resolve(connectionDetails) }

internal fun <B, C> B.applyAwsDefaults(
    defaults: AwsClientDefaults,
): B
    where B: AwsClientBuilder<B, C> =
    apply {
        defaults.region?.let { region(it) }
        defaults.endpointOverride?.let { endpointOverride(it) }
    }

internal fun AwsSyncClientBuilder<*, *>.applyGlobalCustomizers(
    serviceName: String,
    customizers: ObjectProvider<AwsSyncClientCustomizer>,
) {
    val context = AwsClientCustomizationContext(serviceName)
    customizers.orderedStream().forEach { it.customize(context, this) }
}

internal fun AwsAsyncClientBuilder<*, *>.applyGlobalCustomizers(
    serviceName: String,
    customizers: ObjectProvider<AwsAsyncClientCustomizer>,
) {
    val context = AwsClientCustomizationContext(serviceName)
    customizers.orderedStream().forEach { it.customize(context, this) }
}

internal fun <B> B.applyServiceCustomizers(
    customizers: ObjectProvider<AwsClientCustomizer<B>>,
): B =
    apply {
        customizers.orderedStream().forEach { it.customize(this) }
    }
