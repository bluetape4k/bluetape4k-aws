package io.bluetape4k.aws.kotlin.secretsmanager

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 값이 노출되지 않도록 보호하는 AWS Kotlin SDK 보안 값입니다.
 *
 * ## 계약
 * - [reveal]은 원본 값을 반환하므로 명시적인 소비자 경계에서만 호출해야 합니다.
 * - 진단 출력에서는 항상 값을 숨깁니다.
 * - 직렬화된 바이트에는 원본 값이 포함되므로 신뢰할 수 있는 프로세스 또는 저장소 경계 안에서만 다뤄야 합니다.
 *
 * ```kotlin
 * val secret = awsSecretValueOf("raw-secret")
 * secret.reveal() // "raw-secret"
 * secret.toString() // "****"
 * ```
 */
class AwsSecretValue private constructor(private val value: String): Serializable {

    /**
     * 호출자의 명시적인 소비자 경계에서 사용할 원본 보안 값을 반환합니다.
     */
    fun reveal(): String = value

    private fun readResolve(): Any = AwsSecretValue(value)

    override fun toString(): String = REDACTED

    override fun equals(other: Any?): Boolean =
        this === other || other is AwsSecretValue && MessageDigest.isEqual(
            value.toByteArray(StandardCharsets.UTF_8),
            other.value.toByteArray(StandardCharsets.UTF_8),
        )

    override fun hashCode(): Int = REDACTED.hashCode()

    companion object {
        private const val serialVersionUID: Long = 3840856623750752610L

        /**
         * [toString]이 사용하는 마스킹 문자열입니다.
         */
        const val REDACTED: String = "****"

        /**
         * 보안 값을 숨기는 래퍼를 생성합니다.
         */
        operator fun invoke(value: String): AwsSecretValue {
            value.requireNotBlank("value")
            return AwsSecretValue(value)
        }

        /**
         * 보안 값을 숨기는 래퍼를 생성합니다.
         */
        fun of(value: String): AwsSecretValue = invoke(value)
    }
}

/**
 * AWS Kotlin SDK 보안 값을 숨기는 래퍼를 생성합니다.
 */
fun awsSecretValueOf(value: String): AwsSecretValue =
    AwsSecretValue.of(value)
