package io.bluetape4k.aws.rds

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Redacted Amazon RDS IAM authentication token value.
 *
 * ## Contract
 * - [reveal] returns the raw token and should be called only at an explicit
 *   authentication boundary.
 * - Diagnostic output is always redacted.
 * - Serialized bytes contain the raw token and must stay inside trusted
 *   process or storage boundaries.
 *
 * ```kotlin
 * val token = awsRdsIamAuthTokenOf("signed-token")
 * token.reveal() // "signed-token"
 * token.toString() // "****"
 * ```
 */
class AwsRdsIamAuthToken private constructor(private val value: String): Serializable {

    /**
     * Returns the raw token value for the caller's authentication boundary.
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
         * Redacted marker used by [toString].
         */
        const val REDACTED: String = "****"

        /**
         * Creates a redacted token wrapper.
         */
        operator fun invoke(value: String): AwsRdsIamAuthToken {
            value.requireNotBlank("value")
            return AwsRdsIamAuthToken(value)
        }

        /**
         * Creates a redacted token wrapper.
         */
        fun of(value: String): AwsRdsIamAuthToken = invoke(value)
    }
}

/**
 * Creates a redacted Amazon RDS IAM authentication token wrapper.
 */
fun awsRdsIamAuthTokenOf(value: String): AwsRdsIamAuthToken =
    AwsRdsIamAuthToken.of(value)
