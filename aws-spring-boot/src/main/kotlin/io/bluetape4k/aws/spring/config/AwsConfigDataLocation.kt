package io.bluetape4k.aws.spring.config

import io.bluetape4k.aws.spring.s3.S3ConfigFormat
import io.bluetape4k.aws.spring.secretsmanager.SecretFormat

/** ConfigData location이 사용하는 AWS backend입니다. */
internal enum class AwsConfigDataBackend(
    val prefix: String,
    val key: String,
) {
    S3("aws-s3:", "s3"),
    PARAMETER_STORE("aws-parameterstore:", "parameter-store"),
    SECRETS_MANAGER("aws-secretsmanager:", "secrets-manager"),
}

/** Resolver가 검증한 backend별 원격 source입니다. */
internal sealed interface AwsConfigDataSource {
    val canonicalSource: String

    data class S3(
        val bucket: String,
        val key: String,
        val prefix: String?,
        val format: S3ConfigFormat,
    ) : AwsConfigDataSource {
        override val canonicalSource: String
            get() = "/$bucket/$key"
    }

    data class ParameterStore(
        val path: String,
        val prefix: String?,
        val recursive: Boolean,
        val withDecryption: Boolean,
    ) : AwsConfigDataSource {
        override val canonicalSource: String
            get() = path
    }

    data class SecretsManager(
        val secretId: String,
        val prefix: String?,
        val format: SecretFormat,
    ) : AwsConfigDataSource {
        override val canonicalSource: String
            get() = secretId
    }
}

/** Spring Boot ConfigData resolver가 loader에 전달할 검증된 불변 metadata입니다. */
internal data class AwsConfigDataLocation(
    val backend: AwsConfigDataBackend,
    val source: AwsConfigDataSource,
    val optional: Boolean,
    val options: Map<String, String>,
) {
    val canonicalDecodedLocation: String
        get() = buildString {
            append(source.canonicalSource)
            if (options.isNotEmpty()) {
                append('?')
                options.toSortedMap().entries.joinTo(this, separator = "&") { (key, value) ->
                    "$key=$value"
                }
            }
        }
}
