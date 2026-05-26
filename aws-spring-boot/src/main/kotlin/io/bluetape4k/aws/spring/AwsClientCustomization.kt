package io.bluetape4k.aws.spring

import software.amazon.awssdk.awscore.client.builder.AwsAsyncClientBuilder
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder
import java.io.Serializable

/**
 * Context passed to global AWS SDK v2 client customizers.
 */
data class AwsClientCustomizationContext(
    val serviceName: String,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = -6912330727448422635L
    }
}

/**
 * Customizes every sync AWS SDK v2 client builder created by this module.
 */
fun interface AwsSyncClientCustomizer {
    fun customize(
        context: AwsClientCustomizationContext,
        builder: AwsSyncClientBuilder<*, *>,
    )
}

/**
 * Customizes every async AWS SDK v2 client builder created by this module.
 */
fun interface AwsAsyncClientCustomizer {
    fun customize(
        context: AwsClientCustomizationContext,
        builder: AwsAsyncClientBuilder<*, *>,
    )
}

/**
 * Customizes a specific AWS SDK v2 builder type.
 */
fun interface AwsClientCustomizer<B> {
    fun customize(builder: B)
}
