package io.bluetape4k.aws.spring.env

import io.bluetape4k.logging.KLogging
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.json.JsonParserFactory
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.env.MapPropertySource
import org.springframework.util.ClassUtils
import java.io.Serializable
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal const val COMMAND_LINE_PROPERTY_SOURCE_NAME = "commandLineArgs"
private const val AWS_ENABLED_PROPERTY = "bluetape4k.aws.enabled"

internal fun ConfigurableEnvironment.isAwsEnabled(): Boolean =
    getProperty(AWS_ENABLED_PROPERTY, Boolean::class.java, true)

internal data class AwsLoadedPropertySource(
    val name: String,
    val values: Map<String, Any>,
    val reload: () -> Map<String, Any>?,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal fun requireRegionWhenEndpointOverride(
    endpointOverride: URI?,
    region: String?,
    propertyPrefix: String,
) {
    require(endpointOverride == null || !region.isNullOrBlank()) {
        "$propertyPrefix.region is required when endpointOverride is configured."
    }
}

internal fun requireOptionalName(value: String?, name: String) {
    value?.let { require(it.isNotBlank()) { "$name must not be blank." } }
}

internal fun requireAwsSdkClass(
    className: String,
    dependencyNotation: String,
    classLoader: ClassLoader?,
) {
    if (!ClassUtils.isPresent(className, classLoader)) {
        throw IllegalStateException(
            "AWS SDK class '$className' is required. Add runtime dependency '$dependencyNotation'."
        )
    }
}

/** legacy 진단에서 property-source 이름과 원격 식별자를 opaque 값으로 바꿉니다. */
internal fun opaqueAwsDiagnosticIdentity(
    backend: String,
    identifier: String,
): String {
    val input = "$backend\u0000$identifier"
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
        .take(12)
    return "bluetape4k.aws.configdata.$backend.$digest"
}

internal fun opaqueAwsPropertySourceIdentity(name: String): String {
    val backend = when {
        name.startsWith("bluetape4k.aws.s3.config.") -> "s3"
        name.startsWith("bluetape4k.aws.parameter-store.") -> "parameter-store"
        name.startsWith("bluetape4k.aws.secrets-manager.") -> "secrets-manager"
        else -> "legacy"
    }
    return opaqueAwsDiagnosticIdentity(backend, name)
}

internal inline fun <reified T: Any> ConfigurableEnvironment.bindOrCreate(prefix: String): T =
    Binder.get(this).bindOrCreate(prefix, T::class.java)

internal fun ConfigurableEnvironment.addAwsPropertySource(
    loaded: AwsLoadedPropertySource,
    refreshInterval: Duration?,
    clock: Clock = Clock.systemUTC(),
) {
    if (loaded.values.isEmpty()) {
        return
    }

    val source = if (refreshInterval == null) {
        MapPropertySource(loaded.name, loaded.values)
    } else {
        RefreshingAwsMapPropertySource(
            name = loaded.name,
            initialValues = loaded.values,
            refreshInterval = refreshInterval,
            reload = loaded.reload,
            clock = clock,
        )
    }

    addAwsPropertySource(source)
}

private fun ConfigurableEnvironment.addAwsPropertySource(source: org.springframework.core.env.PropertySource<*>) {
    val previousAwsSource = propertySources
        .map { it.name }
        .lastOrNull { it.startsWith("bluetape4k.aws.") }

    when {
        previousAwsSource != null -> propertySources.addAfter(previousAwsSource, source)
        propertySources.contains(COMMAND_LINE_PROPERTY_SOURCE_NAME) ->
            propertySources.addAfter(COMMAND_LINE_PROPERTY_SOURCE_NAME, source)
        else -> propertySources.addFirst(source)
    }
}

internal class RefreshingAwsMapPropertySource(
    name: String,
    initialValues: Map<String, Any>,
    private val refreshInterval: Duration,
    private val reload: () -> Map<String, Any>?,
    private val clock: Clock = Clock.systemUTC(),
): EnumerablePropertySource<Map<String, Any>>(name, initialValues.toMap()) {

    companion object: KLogging()

    private val refreshLock = ReentrantLock()

    @Volatile
    private var currentValues: Map<String, Any> = initialValues.toMap()

    @Volatile
    private var nextRefreshAt: Instant = clock.instant().plus(refreshInterval)

    override fun getProperty(name: String): Any? {
        refreshIfNeeded()
        return currentValues[name]
    }

    override fun containsProperty(name: String): Boolean {
        refreshIfNeeded()
        return currentValues.containsKey(name)
    }

    override fun getPropertyNames(): Array<String> {
        refreshIfNeeded()
        return currentValues.keys.toTypedArray()
    }

    private fun refreshIfNeeded() {
        val now = clock.instant()
        if (now.isBefore(nextRefreshAt)) {
            return
        }

        refreshLock.withLock {
            val lockedNow = clock.instant()
            if (lockedNow.isBefore(nextRefreshAt)) {
                return
            }

            try {
                reload()?.let { values ->
                    currentValues = values.toMap()
                }
            } catch (e: RuntimeException) {
                log.warn(
                    "Keeping previous AWS property source values after refresh failure: " +
                        "${opaqueAwsPropertySourceIdentity(name)} (${e::class.java.simpleName}).",
                )
            }
            nextRefreshAt = lockedNow.plus(refreshInterval)
        }
    }
}

internal fun flattenJsonObject(
    json: String,
    prefix: String?,
): Map<String, Any> {
    val parsed = JsonParserFactory.getJsonParser().parseMap(json)
    return buildMap {
        flattenValue(parsed, prefix?.trimToNull(), this)
    }
}

internal fun textSecretProperty(
    value: String,
    prefix: String?,
    name: String?,
): Map<String, Any> {
    val key = prefix?.trimToNull() ?: name?.trimToNull()
    require(!key.isNullOrBlank()) {
        "TEXT secret sources require either prefix or name."
    }
    return mapOf(key to value)
}

internal fun parameterPathPropertyKey(
    sourcePath: String,
    parameterName: String,
    prefix: String?,
): String? {
    val normalizedPath = sourcePath.trimEnd('/')
    val relative = parameterName.removePrefix(normalizedPath).trimStart('/')
    if (relative.isBlank()) {
        return null
    }
    val key = relative.split('/')
        .filter { it.isNotBlank() }
        .joinToString(".")
    return joinPropertyKey(prefix, key)
}

private fun flattenValue(
    value: Any?,
    key: String?,
    target: MutableMap<String, Any>,
) {
    when (value) {
        is Map<*, *> -> value.forEach { (entryKey, entryValue) ->
            flattenValue(entryValue, joinPropertyKey(key, entryKey.toString()), target)
        }
        is Collection<*> -> value.forEachIndexed { index, entryValue ->
            val childKey = if (key == null) "[$index]" else "$key[$index]"
            flattenValue(entryValue, childKey, target)
        }
        null -> Unit
        else -> {
            require(!key.isNullOrBlank()) { "JSON scalar value requires a property key." }
            target[key] = value
        }
    }
}

internal fun joinPropertyKey(prefix: String?, key: String): String =
    listOfNotNull(prefix?.trimToNull(), key.trimToNull()).joinToString(".")

internal fun String.trimToNull(): String? =
    trim().takeIf { it.isNotEmpty() }
