package io.bluetape4k.aws.ktor.imds

import aws.sdk.kotlin.runtime.config.imds.EndpointMode
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Locale
import java.util.Properties

internal data class ImdsResolvedProfile(
    val profileName: String,
    val endpoint: URI?,
    val endpointMode: EndpointMode?,
    val metadataServiceTimeout: Duration?,
)

/** Reads only the endpoint and timeout scalar settings used by the IMDS client. */
internal class ImdsProfileResolver(
    private val systemProperties: Map<String, String> = currentSystemProperties(),
    private val environment: Map<String, String> = System.getenv(),
    private val userHome: Path = Path.of(System.getProperty("user.home")),
) {

    fun resolve(resolveEndpoint: Boolean = true, resolveEndpointMode: Boolean = true): ImdsResolvedProfile {
        val profileName =
            systemProperties[PROFILE_PROPERTY].nonBlank()
                ?: environment[PROFILE_ENVIRONMENT].nonBlank()
                ?: DEFAULT_PROFILE

        val configPath = resolvePath(CONFIG_PATH_PROPERTY, CONFIG_PATH_ENVIRONMENT, ".aws/config")
        val credentialsPath = resolvePath(CREDENTIALS_PATH_PROPERTY, CREDENTIALS_PATH_ENVIRONMENT, ".aws/credentials")
        val configValues = readProfile(configPath, profileName, configFile = true)
        val credentialsValues = readProfile(credentialsPath, profileName, configFile = false)

        fun profileValue(key: String): String? = credentialsValues[key] ?: configValues[key]

        val endpointValue = if (resolveEndpoint) {
            systemProperties[ENDPOINT_PROPERTY].nonBlank()
                ?: environment[ENDPOINT_ENVIRONMENT].nonBlank()
                ?: profileValue(ENDPOINT_KEY).nonBlank()
        } else {
            null
        }
        val modeValue = if (resolveEndpointMode && endpointValue == null) {
            systemProperties[ENDPOINT_MODE_PROPERTY].nonBlank()
                ?: environment[ENDPOINT_MODE_ENVIRONMENT].nonBlank()
                ?: profileValue(ENDPOINT_MODE_KEY).nonBlank()
        } else {
            null
        }
        val timeoutValue =
            systemProperties[TIMEOUT_PROPERTY].nonBlank()
                ?: environment[TIMEOUT_ENVIRONMENT].nonBlank()
                ?: profileValue(TIMEOUT_KEY).nonBlank()

        return ImdsResolvedProfile(
            profileName = profileName,
            endpoint = endpointValue?.let(::parseEndpoint),
            endpointMode = modeValue?.let(::parseEndpointMode),
            metadataServiceTimeout = timeoutValue?.let(::parseTimeout),
        )
    }

    private fun resolvePath(property: String, environmentKey: String, defaultRelativePath: String): Path {
        val value = systemProperties[property].nonBlank()
            ?: environment[environmentKey].nonBlank()
            ?: userHome.resolve(defaultRelativePath).toString()
        return expandHome(value)
    }

    private fun expandHome(value: String): Path = when {
        value == "~" -> userHome
        value.startsWith("~/") -> userHome.resolve(value.substring(2))
        else -> Path.of(value)
    }

    private fun readProfile(path: Path, profileName: String, configFile: Boolean): Map<String, String> {
        val sections = try {
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) return emptyMap()
            Files.readAllLines(path).let(::parseIni)
        } catch (_: IOException) {
            return emptyMap()
        } catch (_: SecurityException) {
            return emptyMap()
        }

        if (configFile && profileName == DEFAULT_PROFILE) {
            return sections["profile default"] ?: sections[DEFAULT_PROFILE].orEmpty()
        }

        val section = if (configFile) "profile $profileName" else profileName
        return sections[section].orEmpty()
    }

    private fun parseIni(lines: List<String>): Map<String, Map<String, String>> {
        val sections = linkedMapOf<String, LinkedHashMap<String, String>>()
        var current: LinkedHashMap<String, String>? = null

        lines.forEach { rawLine ->
            val line = rawLine.stripTrailingComment().trim()
            if (line.isEmpty() || line.startsWith('#') || line.startsWith(';')) return@forEach

            if (line.startsWith('[') && line.endsWith(']')) {
                val sectionName = line.substring(1, line.lastIndex).trim()
                current = sections.getOrPut(sectionName) { linkedMapOf() }
                return@forEach
            }

            val section = current ?: return@forEach
            val separator = line.indexOf('=')
            if (separator <= 0) return@forEach

            val key = line.substring(0, separator).trim().lowercase(Locale.ROOT)
            val value = line.substring(separator + 1).trim().removeWrappingQuotes()
            if (key in PROFILE_KEYS) section[key] = value
        }

        return sections
    }

    private fun parseEndpoint(value: String): URI = try {
        URI(value)
    } catch (cause: Exception) {
        throw IllegalStateException("Invalid $ENDPOINT_KEY value in AWS shared configuration.", cause)
    }

    private fun parseEndpointMode(value: String): EndpointMode = when (value.lowercase(Locale.ROOT)) {
        "ipv4" -> EndpointMode.IPv4
        "ipv6" -> EndpointMode.IPv6
        else -> throw IllegalStateException("Invalid $ENDPOINT_MODE_KEY value '$value' in AWS shared configuration.")
    }

    private fun parseTimeout(value: String): Duration {
        val seconds = try {
            BigDecimal(value)
        } catch (cause: NumberFormatException) {
            throw IllegalStateException("Invalid $TIMEOUT_KEY value '$value' in AWS shared configuration.", cause)
        }
        if (seconds <= BigDecimal.ZERO) {
            throw IllegalStateException("$TIMEOUT_KEY must be greater than zero.")
        }

        val millis = try {
            seconds.multiply(MILLIS_PER_SECOND).setScale(0, RoundingMode.HALF_UP).longValueExact()
        } catch (cause: ArithmeticException) {
            throw IllegalStateException("Invalid $TIMEOUT_KEY value '$value' in AWS shared configuration.", cause)
        }
        if (millis <= 0L) {
            throw IllegalStateException("$TIMEOUT_KEY must be at least one millisecond.")
        }
        return Duration.ofMillis(millis)
    }

    private fun String?.nonBlank(): String? = this?.trim()?.takeIf(String::isNotEmpty)

    private fun String.removeWrappingQuotes(): String =
        if (length >= 2 && first() == last() && first() in listOf('\'', '"')) substring(1, lastIndex) else this

    private fun String.stripTrailingComment(): String {
        var quote: Char? = null
        forEachIndexed { index, char ->
            if (quote != null) {
                if (char == quote) quote = null
            } else if (char == '\'' || char == '"') {
                quote = char
            } else if ((char == '#' || char == ';') && (index == 0 || this[index - 1].isWhitespace())) {
                return substring(0, index).trimEnd()
            }
        }
        return this
    }

    private companion object {
        const val DEFAULT_PROFILE = "default"
        const val PROFILE_PROPERTY = "aws.profile"
        const val PROFILE_ENVIRONMENT = "AWS_PROFILE"
        const val CONFIG_PATH_PROPERTY = "aws.configFile"
        const val CONFIG_PATH_ENVIRONMENT = "AWS_CONFIG_FILE"
        const val CREDENTIALS_PATH_PROPERTY = "aws.sharedCredentialsFile"
        const val CREDENTIALS_PATH_ENVIRONMENT = "AWS_SHARED_CREDENTIALS_FILE"
        const val ENDPOINT_PROPERTY = "aws.ec2MetadataServiceEndpoint"
        const val ENDPOINT_ENVIRONMENT = "AWS_EC2_METADATA_SERVICE_ENDPOINT"
        const val ENDPOINT_MODE_PROPERTY = "aws.ec2MetadataServiceEndpointMode"
        const val ENDPOINT_MODE_ENVIRONMENT = "AWS_EC2_METADATA_SERVICE_ENDPOINT_MODE"
        const val TIMEOUT_PROPERTY = "aws.ec2MetadataServiceTimeout"
        const val TIMEOUT_ENVIRONMENT = "AWS_METADATA_SERVICE_TIMEOUT"
        const val ENDPOINT_KEY = "ec2_metadata_service_endpoint"
        const val ENDPOINT_MODE_KEY = "ec2_metadata_service_endpoint_mode"
        const val TIMEOUT_KEY = "metadata_service_timeout"
        val PROFILE_KEYS = setOf(ENDPOINT_KEY, ENDPOINT_MODE_KEY, TIMEOUT_KEY)
        val MILLIS_PER_SECOND = BigDecimal("1000")

        fun currentSystemProperties(): Map<String, String> {
            val properties = Properties().apply { putAll(System.getProperties()) }
            return properties.stringPropertyNames().associateWith(properties::getProperty)
        }
    }
}
