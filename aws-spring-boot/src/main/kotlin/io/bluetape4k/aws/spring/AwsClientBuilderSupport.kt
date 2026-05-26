package io.bluetape4k.aws.spring

import org.springframework.beans.factory.ObjectProvider
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
