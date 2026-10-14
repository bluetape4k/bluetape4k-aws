package io.bluetape4k.aws.kotlin.sts

import io.bluetape4k.support.requireInRange

private const val ASSUME_ROLE_MIN_DURATION_SECONDS = 900
private const val ASSUME_ROLE_MAX_DURATION_SECONDS = 43_200
private const val SESSION_TOKEN_MIN_DURATION_SECONDS = 900
private const val SESSION_TOKEN_MAX_DURATION_SECONDS = 129_600

/**
 * AssumeRole API의 `durationSeconds` 계약을 검증한다.
 *
 * AWS STS AssumeRole은 기본적으로 900..43200초 범위의 값을 허용한다.
 */
internal fun requireValidAssumeRoleDuration(durationSeconds: Int) {
    durationSeconds.requireInRange(
        ASSUME_ROLE_MIN_DURATION_SECONDS,
        ASSUME_ROLE_MAX_DURATION_SECONDS,
        "durationSeconds"
    )
}

/**
 * GetSessionToken API의 `durationSeconds` 계약을 검증한다.
 *
 * AWS STS GetSessionToken은 기본적으로 900..129600초 범위의 값을 허용한다.
 */
internal fun requireValidSessionTokenDuration(durationSeconds: Int) {
    durationSeconds.requireInRange(
        SESSION_TOKEN_MIN_DURATION_SECONDS,
        SESSION_TOKEN_MAX_DURATION_SECONDS,
        "durationSeconds"
    )
}
