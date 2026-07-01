package io.bluetape4k.aws.ktor.sts

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

private const val ASSUME_ROLE_MIN_DURATION_SECONDS = 900
private const val ASSUME_ROLE_MAX_DURATION_SECONDS = 43_200
private const val SESSION_TOKEN_MIN_DURATION_SECONDS = 900
private const val SESSION_TOKEN_MAX_DURATION_SECONDS = 129_600

/**
 * Request for assuming an IAM role through STS.
 *
 * ## Contract
 *
 * [durationSeconds] follows the standard STS AssumeRole range of 900 to 43,200
 * seconds. The response is returned as the raw AWS SDK object by
 * [StsKtorOperations.assumeRole].
 */
data class StsAssumeRoleRequest(
    val roleArn: String,
    val sessionName: String,
    val durationSeconds: Int = 3600,
    val externalId: String? = null,
): Serializable {

    init {
        roleArn.requireNotBlank("roleArn")
        sessionName.requireNotBlank("sessionName")
        externalId?.requireNotBlank("externalId")
        require(durationSeconds in ASSUME_ROLE_MIN_DURATION_SECONDS..ASSUME_ROLE_MAX_DURATION_SECONDS) {
            "durationSeconds must be between $ASSUME_ROLE_MIN_DURATION_SECONDS and " +
                    "$ASSUME_ROLE_MAX_DURATION_SECONDS for AssumeRole, but was $durationSeconds."
        }
    }

    companion object {
        private const val serialVersionUID: Long = -7237515026828012746L
    }
}

/**
 * Request for obtaining STS session credentials.
 *
 * ## Contract
 *
 * [durationSeconds] follows the standard STS GetSessionToken range of 900 to
 * 129,600 seconds. MFA fields are optional but, when set, must be nonblank.
 */
data class StsSessionTokenRequest(
    val durationSeconds: Int = 3600,
    val serialNumber: String? = null,
    val tokenCode: String? = null,
): Serializable {

    init {
        serialNumber?.requireNotBlank("serialNumber")
        tokenCode?.requireNotBlank("tokenCode")
        require(durationSeconds in SESSION_TOKEN_MIN_DURATION_SECONDS..SESSION_TOKEN_MAX_DURATION_SECONDS) {
            "durationSeconds must be between $SESSION_TOKEN_MIN_DURATION_SECONDS and " +
                    "$SESSION_TOKEN_MAX_DURATION_SECONDS for GetSessionToken, but was $durationSeconds."
        }
    }

    companion object {
        private const val serialVersionUID: Long = -8684755858495812782L
    }
}
