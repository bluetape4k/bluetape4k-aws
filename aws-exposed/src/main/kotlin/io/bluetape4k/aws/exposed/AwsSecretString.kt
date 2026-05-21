package io.bluetape4k.aws.exposed

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * Redacted wrapper for secret text values.
 *
 * Use [reveal] only at the boundary that must pass the secret to a JDBC driver
 * or connection pool. Diagnostic output always returns [REDACTED].
 */
@JvmInline
value class AwsSecretString(private val value: String): Serializable {

    init {
        value.requireNotBlank("value")
    }

    /**
     * Returns the raw secret value for connection construction.
     */
    fun reveal(): String = value

    override fun toString(): String = REDACTED

    companion object {
        private const val serialVersionUID: Long = 5414882260345112140L

        /**
         * Redacted marker used by [toString].
         */
        const val REDACTED: String = "****"

        /**
         * Creates a secret wrapper.
         */
        fun of(value: String): AwsSecretString = AwsSecretString(value)
    }
}
