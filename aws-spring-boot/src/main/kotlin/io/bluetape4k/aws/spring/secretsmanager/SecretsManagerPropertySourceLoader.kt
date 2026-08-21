package io.bluetape4k.aws.spring.secretsmanager

import io.bluetape4k.aws.spring.env.AwsLoadedPropertySource
import io.bluetape4k.aws.spring.env.flattenJsonObject
import io.bluetape4k.aws.spring.env.opaqueAwsDiagnosticIdentity
import io.bluetape4k.aws.spring.env.textSecretProperty
import io.bluetape4k.logging.KLogging
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException

internal object SecretsManagerPropertySourceLoader: KLogging() {

    fun load(properties: SecretsManagerProperties): List<AwsLoadedPropertySource> =
        buildClient(properties)
            .use { client ->
                properties.sources.mapNotNull { source ->
                    loadSource(client, properties, source)?.let { values ->
                        AwsLoadedPropertySource(
                            name = source.propertySourceName,
                            values = values,
                            reload = { loadSingleSource(properties, source) },
                        )
                    }
                }
            }

    private fun loadSingleSource(
        properties: SecretsManagerProperties,
        source: SecretsManagerProperties.Source,
    ): Map<String, Any>? =
        buildClient(properties).use { client ->
            loadSource(client, properties, source)
        }

    private fun buildClient(properties: SecretsManagerProperties): SecretsManagerClient =
        SecretsManagerClient.builder()
            .apply {
                properties.region?.let { region(Region.of(it)) }
                properties.endpointOverride?.let { endpointOverride(it) }
            }
            .build()

    private fun loadSource(
        client: SecretsManagerClient,
        properties: SecretsManagerProperties,
        source: SecretsManagerProperties.Source,
    ): Map<String, Any>? =
        try {
            load(client, source)
        } catch (e: ResourceNotFoundException) {
            handleFailure(properties, source, e)
        } catch (e: RuntimeException) {
            handleFailure(properties, source, e)
        }

    /** ConfigData adapter가 소유한 client로 단일 source를 읽는 throw-only 경계입니다. */
    internal fun load(
        client: SecretsManagerClient,
        source: SecretsManagerProperties.Source,
    ): Map<String, Any> {
        val response = client.getSecretValue { it.secretId(source.secretId) }
        val secretString = response.secretString()
            ?: throw IllegalStateException("Secret has no SecretString.")

        return when (source.format) {
            SecretFormat.JSON -> flattenJsonObject(secretString, source.prefix)
            SecretFormat.TEXT -> textSecretProperty(secretString, source.prefix, source.name)
        }
    }

    private fun handleFailure(
        properties: SecretsManagerProperties,
        source: SecretsManagerProperties.Source,
        error: RuntimeException,
    ): Map<String, Any>? {
        if (source.optional || !properties.failFast) {
            log.warn(
                "Skipping Secrets Manager source " +
                    "${opaqueAwsDiagnosticIdentity("secrets-manager", source.propertySourceName)} " +
                    "(${error::class.java.simpleName}).",
            )
            return null
        }
        throw error
    }
}
