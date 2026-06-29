package io.bluetape4k.aws.secretsmanager

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Redacted AWS secret value.
 *
 * ## Contract
 * - [reveal] returns the raw value and should be called only at an explicit
 *   consumer boundary.
 * - Diagnostic output is always redacted.
 * - Serialized bytes contain the raw value and must stay inside trusted
 *   process or storage boundaries.
 *
 * ```kotlin
 * val secret = awsSecretValueOf("raw-secret")
 * secret.reveal() // "raw-secret"
 * secret.toString() // "****"
 * ```
 */
class AwsSecretValue private constructor(private val value: String): Serializable {

    /**
     * Returns the raw secret value for the caller's explicit consumer boundary.
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
        private const val serialVersionUID: Long = 5999793257085907617L

        /**
         * Redacted marker used by [toString].
         */
        const val REDACTED: String = "****"

        /**
         * Creates a redacted secret value wrapper.
         */
        operator fun invoke(value: String): AwsSecretValue {
            value.requireNotBlank("value")
            return AwsSecretValue(value)
        }

        /**
         * Creates a redacted secret value wrapper.
         */
        fun of(value: String): AwsSecretValue = invoke(value)
    }
}

/**
 * Creates a redacted AWS secret value wrapper.
 */
fun awsSecretValueOf(value: String): AwsSecretValue =
    AwsSecretValue.of(value)
