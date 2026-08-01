package io.bluetape4k.aws.rds

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 값이 노출되지 않도록 보호하는 Amazon RDS IAM 인증 토큰입니다.
 *
 * ## 계약
 * - [reveal]은 원본 토큰을 반환하므로 명시적인 인증 경계에서만 호출해야 합니다.
 * - 진단 출력에서는 항상 토큰을 숨깁니다.
 * - 직렬화된 바이트에는 원본 토큰이 포함되므로 신뢰할 수 있는 프로세스 또는 저장소 경계 안에서만 다뤄야 합니다.
 *
 * ```kotlin
 * val token = awsRdsIamAuthTokenOf("signed-token")
 * token.reveal() // "signed-token"
 * token.toString() // "****"
 * ```
 */
class AwsRdsIamAuthToken private constructor(private val value: String): Serializable {

    /**
     * 호출자의 인증 경계에서 사용할 원본 토큰 값을 반환합니다.
     */
    fun reveal(): String = value

    private fun readResolve(): Any = AwsRdsIamAuthToken(value)

    override fun toString(): String = REDACTED

    override fun equals(other: Any?): Boolean =
        this === other || other is AwsRdsIamAuthToken && MessageDigest.isEqual(
            value.toByteArray(StandardCharsets.UTF_8),
            other.value.toByteArray(StandardCharsets.UTF_8),
        )

    override fun hashCode(): Int = REDACTED.hashCode()

    companion object: KLogging() {
        private const val serialVersionUID: Long = -2980523846838225204L

        /**
         * [toString]이 사용하는 마스킹 문자열입니다.
         */
        const val REDACTED: String = "****"

        /**
         * 토큰 값을 숨기는 래퍼를 생성합니다.
         */
        operator fun invoke(value: String): AwsRdsIamAuthToken {
            value.requireNotBlank("value")
            return AwsRdsIamAuthToken(value)
        }

        /**
         * 토큰 값을 숨기는 래퍼를 생성합니다.
         */
        fun of(value: String): AwsRdsIamAuthToken = invoke(value)
    }
}

/**
 * Amazon RDS IAM 인증 토큰 값을 숨기는 래퍼를 생성합니다.
 */
fun awsRdsIamAuthTokenOf(value: String): AwsRdsIamAuthToken =
    AwsRdsIamAuthToken.of(value)
