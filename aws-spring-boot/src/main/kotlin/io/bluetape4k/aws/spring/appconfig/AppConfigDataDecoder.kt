package io.bluetape4k.aws.spring.appconfig

import io.bluetape4k.aws.spring.env.joinPropertyKey
import io.bluetape4k.aws.spring.env.trimToNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.toUtf8String
import org.springframework.boot.env.PropertiesPropertySourceLoader
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.json.JsonParserFactory
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.env.PropertySource
import org.springframework.core.io.ByteArrayResource
import java.util.*

/** AppConfig Data payload를 Spring Environment가 사용할 immutable map으로 변환합니다. */
internal object AppConfigDataDecoder: KLogging() {

    private val propertiesLoader = PropertiesPropertySourceLoader()
    private val yamlLoader = YamlPropertySourceLoader()

    fun decode(
        payload: ByteArray,
        contentType: String?,
        format: AppConfigFormat,
        prefix: String?,
        maxPayloadBytes: Int = MAX_PAYLOAD_BYTES,
        maxDepth: Int = MAX_FLATTEN_DEPTH,
        maxPropertyCount: Int = MAX_PROPERTY_COUNT,
    ): Map<String, Any> {
        maxPayloadBytes.requirePositiveNumber("maxPayloadBytes")
        maxDepth.requireGe(0, "maxDepth")
        maxPropertyCount.requirePositiveNumber("maxPropertyCount")
        payload.size.requireLe(maxPayloadBytes) { "AWS AppConfig payload exceeds the configured byte budget." }

        val resolvedFormat = format.resolve(contentType)
        val normalizedPrefix = prefix?.trimToNull()
        val sourceName = "bluetape4k.aws.app-config.payload"
        val text = payload.toUtf8String()

        val values = when (resolvedFormat) {
            AppConfigFormat.PROPERTIES -> loadSpringProperties(sourceName, payload, normalizedPrefix, propertiesLoader)
            AppConfigFormat.YAML -> loadSpringProperties(sourceName, payload, normalizedPrefix, yamlLoader)
            AppConfigFormat.JSON -> flattenJson(text, normalizedPrefix, maxDepth, maxPropertyCount)
            AppConfigFormat.AUTO -> error("AUTO format must be resolved before decoding.")
        }
        require(values.size <= maxPropertyCount) { "AWS AppConfig payload exceeds the property-count budget." }
        values.keys.forEach { key -> validateDepth(key, maxDepth) }
        return values.toMap().apply {
            log.debug { "decoded result=$this" }
        }
    }

    private fun loadSpringProperties(
        sourceName: String,
        payload: ByteArray,
        prefix: String?,
        loader: org.springframework.boot.env.PropertySourceLoader,
    ): Map<String, Any> {
        val resource = ByteArrayResource(payload, sourceName)

        return loader.load(sourceName, resource)
            .flatMap { it.entries() }
            .associateTo(linkedMapOf()) { (name, value) ->
                joinPropertyKey(prefix, name) to value
            }
    }

    private fun flattenJson(
        text: String,
        prefix: String?,
        maxDepth: Int,
        maxPropertyCount: Int,
    ): Map<String, Any> {
        val parsed = JsonParserFactory.getJsonParser().parseMap(text)
        val values = linkedMapOf<String, Any>()
        flattenValue(parsed, prefix, 0, maxDepth, maxPropertyCount, values)
        return values
    }

    private fun flattenValue(
        value: Any?,
        key: String?,
        depth: Int,
        maxDepth: Int,
        maxPropertyCount: Int,
        target: MutableMap<String, Any>,
    ) {
        depth.requireLe(maxDepth) { "AWS AppConfig payload exceeds the flatten-depth budget." }

        when (value) {
            is Map<*, *> -> value.forEach { (entryKey, entryValue) ->
                val childKey = joinPropertyKey(key, entryKey.toString())
                flattenValue(
                    value = entryValue,
                    key = childKey,
                    depth = depth + 1,
                    maxDepth = maxDepth,
                    maxPropertyCount = maxPropertyCount,
                    target = target,
                )
            }

            is Collection<*> -> value.forEachIndexed { index, entryValue ->
                val childKey = if (key == null) "[$index]" else "$key[$index]"
                flattenValue(
                    value = entryValue,
                    key = childKey,
                    depth = depth + 1,
                    maxDepth = maxDepth,
                    maxPropertyCount = maxPropertyCount,
                    target = target,
                )
            }

            null -> Unit
            else -> {
                key.requireNotBlank { "AWS AppConfig scalar payload requires a property key." }
                target[key!!] = value
                target.size.requireLe(maxPropertyCount) { "AWS AppConfig payload exceeds the property-count budget." }
            }
        }
    }

    private fun AppConfigFormat.resolve(contentType: String?): AppConfigFormat = when (this) {
        AppConfigFormat.AUTO -> when {
            contentType.isNullOrBlank() -> AppConfigFormat.PROPERTIES
            contentType.lowercase(Locale.ROOT).contains("json") -> AppConfigFormat.JSON
            contentType.lowercase(Locale.ROOT).contains("yaml") || contentType.lowercase(Locale.ROOT).contains("yml") ->
                AppConfigFormat.YAML

            contentType.lowercase(Locale.ROOT).contains("text") ||
                    contentType.lowercase(Locale.ROOT).contains("properties") -> AppConfigFormat.PROPERTIES

            else -> throw IllegalArgumentException("Unsupported AWS AppConfig content type.")
        }

        else -> this
    }

    private fun PropertySource<*>.entries(): List<Pair<String, Any>> =
        when (this) {
            is EnumerablePropertySource<*> -> propertyNames.mapNotNull { name ->
                getProperty(name)?.let { name to it }
            }

            else -> emptyList()
        }

    private fun validateDepth(key: String, maxDepth: Int) {
        val depth = key.count { it == '.' } + key.count { it == '[' }
        require(depth <= maxDepth) { "AWS AppConfig payload exceeds the flatten-depth budget." }
    }

    private const val MAX_PAYLOAD_BYTES = 1024 * 1024
    private const val MAX_FLATTEN_DEPTH = 32
    private const val MAX_PROPERTY_COUNT = 10_000
}
