package io.bluetape4k.aws.spring.s3

import io.bluetape4k.aws.spring.env.AwsLoadedPropertySource
import io.bluetape4k.aws.spring.env.flattenJsonObject
import io.bluetape4k.aws.spring.env.joinPropertyKey
import io.bluetape4k.logging.KLogging
import org.springframework.boot.env.PropertiesPropertySourceLoader
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.env.PropertySource
import org.springframework.core.io.ByteArrayResource
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.S3Exception
import java.nio.charset.StandardCharsets
import java.util.Locale

internal object S3ConfigPropertySourceLoader: KLogging() {

    private val propertiesLoader = PropertiesPropertySourceLoader()
    private val yamlLoader = YamlPropertySourceLoader()

    fun load(properties: S3ConfigProperties): List<AwsLoadedPropertySource> =
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
        properties: S3ConfigProperties,
        source: S3ConfigProperties.Source,
    ): Map<String, Any>? =
        buildClient(properties).use { client ->
            loadSource(client, properties, source)
        }

    private fun buildClient(properties: S3ConfigProperties): S3Client =
        S3Client.builder()
            .apply {
                properties.region?.let { region(Region.of(it)) }
                properties.endpointOverride?.let { endpointOverride(it) }
                serviceConfiguration {
                    it.pathStyleAccessEnabled(properties.pathStyleAccessEnabled)
                }
            }
            .build()

    private fun loadSource(
        client: S3Client,
        properties: S3ConfigProperties,
        source: S3ConfigProperties.Source,
    ): Map<String, Any>? =
        try {
            val content = client.getObjectAsBytes {
                it.bucket(source.bucket)
                it.key(source.key)
            }.asString(StandardCharsets.UTF_8)
            parse(content, source)
        } catch (e: NoSuchBucketException) {
            handleFailure(properties, source, e)
        } catch (e: NoSuchKeyException) {
            handleFailure(properties, source, e)
        } catch (e: S3Exception) {
            handleFailure(properties, source, e)
        } catch (e: SdkException) {
            handleFailure(properties, source, e)
        } catch (e: RuntimeException) {
            handleFailure(properties, source, e)
        }

    private fun parse(
        content: String,
        source: S3ConfigProperties.Source,
    ): Map<String, Any> =
        when (source.resolvedFormat()) {
            S3ConfigFormat.PROPERTIES -> loadSpringPropertySources(source, content, propertiesLoader)
            S3ConfigFormat.YAML -> loadSpringPropertySources(source, content, yamlLoader)
            S3ConfigFormat.JSON -> flattenJsonObject(content, source.prefix)
            S3ConfigFormat.AUTO -> error("AUTO format must be resolved before parsing.")
        }

    private fun loadSpringPropertySources(
        source: S3ConfigProperties.Source,
        content: String,
        loader: org.springframework.boot.env.PropertySourceLoader,
    ): Map<String, Any> {
        val resource = ByteArrayResource(content.toByteArray(StandardCharsets.UTF_8), source.key)
        return loader.load(source.propertySourceName, resource)
            .flatMap { it.entries() }
            .associateTo(linkedMapOf()) { (name, value) -> joinPropertyKey(source.prefix, name) to value }
    }

    private fun PropertySource<*>.entries(): List<Pair<String, Any>> =
        when (this) {
            is EnumerablePropertySource<*> -> propertyNames.mapNotNull { name ->
                getProperty(name)?.let { name to it }
            }
            else -> emptyList()
        }

    private fun S3ConfigProperties.Source.resolvedFormat(): S3ConfigFormat =
        when (format) {
            S3ConfigFormat.AUTO -> when (key.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
                "properties" -> S3ConfigFormat.PROPERTIES
                "yml", "yaml" -> S3ConfigFormat.YAML
                "json" -> S3ConfigFormat.JSON
                else -> S3ConfigFormat.PROPERTIES
            }
            else -> format
        }

    private fun handleFailure(
        properties: S3ConfigProperties,
        source: S3ConfigProperties.Source,
        error: RuntimeException,
    ): Map<String, Any>? {
        if (source.optional || !properties.failFast) {
            log.warn(
                "Skipping S3 config source '${source.propertySourceName}'" +
                    " [bucket=${source.bucket}, key=${source.key}, region=${properties.region}," +
                    " endpoint=${properties.endpointOverride}].",
                error,
            )
            return null
        }
        throw error
    }
}
