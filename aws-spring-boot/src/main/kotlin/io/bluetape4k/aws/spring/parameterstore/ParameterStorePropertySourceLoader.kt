package io.bluetape4k.aws.spring.parameterstore

import io.bluetape4k.aws.spring.env.parameterPathPropertyKey
import io.bluetape4k.logging.KLogging
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException

internal object ParameterStorePropertySourceLoader: KLogging() {

    fun load(properties: ParameterStoreProperties): List<Pair<String, Map<String, Any>>> =
        SsmClient.builder()
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .apply {
                properties.region?.let { region(Region.of(it)) }
                properties.endpointOverride?.let { endpointOverride(it) }
            }
            .build()
            .use { client ->
                properties.sources.mapNotNull { source ->
                    loadSource(client, properties, source)?.let { source.propertySourceName to it }
                }
            }

    private fun loadSource(
        client: SsmClient,
        properties: ParameterStoreProperties,
        source: ParameterStoreProperties.Source,
    ): Map<String, Any>? =
        try {
            loadPath(client, source)
        } catch (e: ParameterNotFoundException) {
            handleFailure(properties, source, e)
        } catch (e: RuntimeException) {
            handleFailure(properties, source, e)
        }

    private fun loadPath(
        client: SsmClient,
        source: ParameterStoreProperties.Source,
    ): Map<String, Any> {
        val values = linkedMapOf<String, Any>()
        var nextToken: String? = null

        do {
            val response = client.getParametersByPath {
                it.path(source.path)
                it.recursive(source.recursive)
                it.withDecryption(source.withDecryption)
                nextToken?.let(it::nextToken)
            }
            response.parameters().forEach { parameter ->
                parameterPathPropertyKey(source.path, parameter.name(), source.prefix)
                    ?.let { key -> values[key] = parameter.value() }
            }
            nextToken = response.nextToken()
        } while (!nextToken.isNullOrBlank())

        return values
    }

    private fun handleFailure(
        properties: ParameterStoreProperties,
        source: ParameterStoreProperties.Source,
        error: RuntimeException,
    ): Map<String, Any>? {
        if (source.optional || !properties.failFast) {
            log.warn("Skipping Parameter Store source '${source.propertySourceName}'.", error)
            return null
        }
        throw error
    }
}

