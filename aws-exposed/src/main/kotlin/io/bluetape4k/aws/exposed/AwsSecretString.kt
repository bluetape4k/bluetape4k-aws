package io.bluetape4k.aws.exposed

import io.bluetape4k.aws.exposed.AwsSecretString.Companion.REDACTED
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Redacted wrapper for secret text values.
 *
 * Use [reveal] only at the boundary that must pass the secret to a JDBC driver
 * or connection pool. Diagnostic output always returns [REDACTED].
 * Java-serialized bytes contain the raw secret and must stay inside trusted
 * process or storage boundaries. [hashCode] intentionally returns a redacted
 * constant, so avoid using this type as a key in large hashed collections.
 */
class AwsSecretString private constructor(private val value: String): Serializable {

    /**
     * Returns the raw secret value for connection construction.
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

    companion object {
        private const val serialVersionUID: Long = 202605220168169L

        /**
         * Redacted marker used by [toString].
         */
        const val REDACTED: String = "****"

        /**
         * Creates a secret wrapper.
         */
        operator fun invoke(value: String): AwsSecretString {
            value.requireNotBlank("value")
            return AwsSecretString(value)
        }

        /**
         * Creates a secret wrapper.
         */
        fun of(value: String): AwsSecretString = invoke(value)
    }
}

fun awsSecretStringOf(value: String): AwsSecretString {
    value.requireNotBlank("value")
    return AwsSecretString.of(value)
}
