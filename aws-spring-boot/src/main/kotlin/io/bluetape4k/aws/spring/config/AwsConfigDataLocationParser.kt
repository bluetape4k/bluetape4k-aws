package io.bluetape4k.aws.spring.config

import io.bluetape4k.aws.spring.s3.S3ConfigFormat
import io.bluetape4k.aws.spring.secretsmanager.SecretFormat
import org.springframework.boot.context.config.ConfigDataLocation
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * `spring.config.import`의 AWS location을 검증된 내부 모델로 변환합니다.
 *
 * URI parser가 query의 percent encoding을 다시 해석하지 않도록 query를 먼저
 * 분리한 다음 각 component를 정확히 한 번만 decode합니다.
 */
@Suppress("TooManyFunctions")
internal class AwsConfigDataLocationParser {

    fun parse(location: ConfigDataLocation): AwsConfigDataLocation {
        val value = location.value
        require(value.isNotEmpty()) { "AWS ConfigData location must not be empty." }
        rejectControl(value)

        val backend = AwsConfigDataBackend.entries.firstOrNull { value.startsWith(it.prefix) }
            ?: throw IllegalArgumentException("Unsupported AWS ConfigData location prefix.")
        val nonPrefixed = value.removePrefix(backend.prefix)
        val (rawSource, rawQuery) = nonPrefixed.splitQuery()
        val source = decodeComponent(rawSource, "source")
        require(source.isNotBlank()) { "AWS ConfigData source must not be blank." }
        val options = parseOptions(rawQuery, backend)

        return AwsConfigDataLocation(
            backend = backend,
            source = parseSource(backend, source, options),
            optional = location.isOptional,
            options = options,
        )
    }

    private fun parseSource(
        backend: AwsConfigDataBackend,
        source: String,
        options: Map<String, String>,
    ): AwsConfigDataSource = when (backend) {
        AwsConfigDataBackend.S3 -> parseS3(source, options)
        AwsConfigDataBackend.PARAMETER_STORE -> parseParameterStore(source, options)
        AwsConfigDataBackend.SECRETS_MANAGER -> parseSecretsManager(source, options)
    }

    private fun parseS3(source: String, options: Map<String, String>): AwsConfigDataSource.S3 {
        val path = source.removePrefix("/")
        val separator = path.indexOf('/')
        require(separator > 0 && separator < path.lastIndex) { "S3 ConfigData source must contain bucket and key." }
        val bucket = path.substring(0, separator).requireNonBlank("S3 bucket")
        val key = path.substring(separator + 1).requireNonBlank("S3 key")
        val format = options[FORMAT]?.let(::parseS3Format) ?: S3ConfigFormat.AUTO
        return AwsConfigDataSource.S3(bucket, key, options[PREFIX], format)
    }

    private fun parseParameterStore(source: String, options: Map<String, String>): AwsConfigDataSource.ParameterStore {
        require(source.startsWith('/')) { "Parameter Store ConfigData source must start with /." }
        return AwsConfigDataSource.ParameterStore(
            path = source.requireNonBlank("Parameter Store path"),
            prefix = options[PREFIX],
            recursive = options[RECURSIVE]?.let(::parseBoolean) ?: true,
            withDecryption = options[WITH_DECRYPTION]?.let(::parseBoolean) ?: true,
        )
    }

    private fun parseSecretsManager(
        source: String,
        options: Map<String, String>,
    ): AwsConfigDataSource.SecretsManager {
        val format = options[FORMAT]?.let(::parseSecretFormat) ?: SecretFormat.JSON
        val prefix = options[PREFIX]
        require(format != SecretFormat.TEXT || !prefix.isNullOrBlank()) {
            "Secrets Manager text format requires prefix."
        }
        return AwsConfigDataSource.SecretsManager(source.requireNonBlank("Secrets Manager secret"), prefix, format)
    }

    private fun parseOptions(rawQuery: String?, backend: AwsConfigDataBackend): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val allowed = when (backend) {
            AwsConfigDataBackend.S3 -> setOf(PREFIX, FORMAT)
            AwsConfigDataBackend.PARAMETER_STORE -> setOf(PREFIX, RECURSIVE, WITH_DECRYPTION)
            AwsConfigDataBackend.SECRETS_MANAGER -> setOf(PREFIX, FORMAT)
        }
        val parsed = linkedMapOf<String, String>()
        rawQuery.split('&').forEach { pair ->
            require(pair.isNotEmpty()) { "AWS ConfigData query option must not be empty." }
            val separator = pair.indexOf('=')
            require(separator > 0 && separator < pair.lastIndex) {
                "AWS ConfigData query option must contain a non-empty key and value."
            }
            val key = decodeComponent(pair.substring(0, separator), "query key")
            val value = decodeComponent(pair.substring(separator + 1), "query value")
            require(key in allowed) { "Unsupported AWS ConfigData query option." }
            require(parsed.put(key, value) == null) { "Duplicate AWS ConfigData query option." }
            when (key) {
                FORMAT -> when (backend) {
                    AwsConfigDataBackend.S3 -> parseS3Format(value)
                    AwsConfigDataBackend.SECRETS_MANAGER -> parseSecretFormat(value)
                    else -> error("format is not supported by this backend")
                }

                RECURSIVE, WITH_DECRYPTION -> parseBoolean(value)
                PREFIX -> value.requireNonBlank("prefix")
            }
        }
        return parsed.toSortedMap()
    }

    private fun parseS3Format(value: String): S3ConfigFormat = when (value.lowercase()) {
        "auto" -> S3ConfigFormat.AUTO
        "properties" -> S3ConfigFormat.PROPERTIES
        "yaml" -> S3ConfigFormat.YAML
        "json" -> S3ConfigFormat.JSON
        else -> throw IllegalArgumentException("Unsupported S3 ConfigData format.")
    }

    private fun parseSecretFormat(value: String): SecretFormat = when (value.lowercase()) {
        "json" -> SecretFormat.JSON
        "text" -> SecretFormat.TEXT
        else -> throw IllegalArgumentException("Unsupported Secrets Manager ConfigData format.")
    }

    private fun parseBoolean(value: String): Boolean = when (value.lowercase()) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("AWS ConfigData boolean option must be true or false.")
    }

    private fun decodeComponent(value: String, label: String): String {
        rejectControl(value)
        return try {
            URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)
        } catch (ex: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid AWS ConfigData $label encoding.", ex)
        }.also { decoded ->
            rejectControl(decoded)
        }
    }

    private fun rejectControl(value: String) {
        require(value.none { it == '\r' || it == '\n' || it == '\u0000' }) {
            "AWS ConfigData location contains a control character."
        }
    }

    private fun String.requireNonBlank(label: String): String {
        require(isNotBlank()) { "$label must not be blank." }
        return this
    }

    private fun String.splitQuery(): Pair<String, String?> {
        val separator = indexOf('?')
        return if (separator < 0) this to null else substring(0, separator) to substring(separator + 1)
    }

    private companion object {
        const val PREFIX = "prefix"
        const val FORMAT = "format"
        const val RECURSIVE = "recursive"
        const val WITH_DECRYPTION = "withDecryption"
    }
}
