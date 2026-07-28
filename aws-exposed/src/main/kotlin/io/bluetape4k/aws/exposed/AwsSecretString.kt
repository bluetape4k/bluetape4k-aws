package io.bluetape4k.aws.exposed

import io.bluetape4k.aws.exposed.AwsSecretString.Companion.REDACTED
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * secret text 값을 redaction-safe 하게 감싸는 wrapper입니다.
 *
 * [reveal]은 JDBC driver 또는 connection pool에 secret을 전달해야 하는 경계에서만 사용합니다.
 * 진단 출력은 항상 [REDACTED]를 반환합니다. Java serialization byte에는 원본 secret이 포함되므로
 * 신뢰된 process 또는 storage 경계 안에만 두어야 합니다. [hashCode]는 의도적으로 redacted 상수를
 * 반환하므로 이 타입을 큰 hash collection의 key로 사용하지 않습니다.
 */
class AwsSecretString private constructor(private val value: String): Serializable {

    /**
     * connection 생성에 사용할 원본 secret 값을 반환합니다.
     */
    fun reveal(): String = value

    private fun readResolve(): Any = AwsSecretString.of(value)

    override fun toString(): String = REDACTED

    override fun equals(other: Any?): Boolean =
        this === other || other is AwsSecretString && MessageDigest.isEqual(
            value.toByteArray(StandardCharsets.UTF_8),
            other.value.toByteArray(StandardCharsets.UTF_8),
        )

    override fun hashCode(): Int = REDACTED.hashCode()

    companion object: KLogging() {
        private const val serialVersionUID: Long = 202605220168169L

        /**
         * [toString]에서 사용하는 redacted marker입니다.
         */
        const val REDACTED: String = "****"

        /**
         * secret wrapper를 생성합니다.
         */
        operator fun invoke(value: String): AwsSecretString {
            value.requireNotBlank("value")
            return AwsSecretString(value)
        }

        /**
         * secret wrapper를 생성합니다.
         */
        fun of(value: String): AwsSecretString = invoke(value)
    }
}

fun awsSecretStringOf(value: String): AwsSecretString {
    value.requireNotBlank("value")
    return AwsSecretString.of(value)
}
