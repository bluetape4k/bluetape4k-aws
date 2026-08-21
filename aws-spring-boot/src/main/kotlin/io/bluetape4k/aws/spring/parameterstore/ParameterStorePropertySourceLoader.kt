package io.bluetape4k.aws.spring.parameterstore

import io.bluetape4k.aws.spring.env.AwsLoadedPropertySource
import io.bluetape4k.aws.spring.env.parameterPathPropertyKey
import io.bluetape4k.aws.spring.env.opaqueAwsDiagnosticIdentity
import io.bluetape4k.logging.KLogging
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException

internal object ParameterStorePropertySourceLoader: KLogging() {

    fun load(properties: ParameterStoreProperties): List<AwsLoadedPropertySource> =
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
        properties: ParameterStoreProperties,
        source: ParameterStoreProperties.Source,
    ): Map<String, Any>? =
        buildClient(properties).use { client ->
            loadSource(client, properties, source)
        }

    private fun buildClient(properties: ParameterStoreProperties): SsmClient =
        SsmClient.builder()
            .apply {
                properties.region?.let { region(Region.of(it)) }
                properties.endpointOverride?.let { endpointOverride(it) }
            }
            .build()

    private fun loadSource(
        client: SsmClient,
        properties: ParameterStoreProperties,
        source: ParameterStoreProperties.Source,
    ): Map<String, Any>? =
        try {
            load(client, source)
        } catch (e: ParameterNotFoundException) {
            handleFailure(properties, source, e)
        } catch (e: RuntimeException) {
            handleFailure(properties, source, e)
        }

    /** ConfigData adapter가 소유한 client로 단일 source를 읽는 throw-only 경계입니다. */
    internal fun load(
        client: SsmClient,
        source: ParameterStoreProperties.Source,
    ): Map<String, Any> = loadPath(client, source)

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
            log.warn(
                "Skipping Parameter Store source " +
                    "${opaqueAwsDiagnosticIdentity("parameter-store", source.propertySourceName)} " +
                    "(${error::class.java.simpleName}).",
            )
            return null
        }
        throw error
    }
}
