package io.bluetape4k.aws.kotlin.sts

private const val ASSUME_ROLE_MIN_DURATION_SECONDS = 900
private const val ASSUME_ROLE_MAX_DURATION_SECONDS = 43_200
private const val SESSION_TOKEN_MIN_DURATION_SECONDS = 900
private const val SESSION_TOKEN_MAX_DURATION_SECONDS = 129_600

/**
 * Validates the `durationSeconds` contract for the AssumeRole API.
 *
 * AWS STS AssumeRole accepts values in the 900..43200 second range by default.
 */
internal fun requireValidAssumeRoleDuration(durationSeconds: Int) {
    require(durationSeconds in ASSUME_ROLE_MIN_DURATION_SECONDS..ASSUME_ROLE_MAX_DURATION_SECONDS) {
        "durationSeconds must be between $ASSUME_ROLE_MIN_DURATION_SECONDS and " +
                "$ASSUME_ROLE_MAX_DURATION_SECONDS for AssumeRole, but was $durationSeconds."
    }
}

/**
 * Validates the `durationSeconds` contract for the GetSessionToken API.
 *
 * AWS STS GetSessionToken accepts values in the 900..129600 second range by default.
 */
internal fun requireValidSessionTokenDuration(durationSeconds: Int) {
    require(durationSeconds in SESSION_TOKEN_MIN_DURATION_SECONDS..SESSION_TOKEN_MAX_DURATION_SECONDS) {
        "durationSeconds must be between $SESSION_TOKEN_MIN_DURATION_SECONDS and " +
                "$SESSION_TOKEN_MAX_DURATION_SECONDS for GetSessionToken, but was $durationSeconds."
    }
}
