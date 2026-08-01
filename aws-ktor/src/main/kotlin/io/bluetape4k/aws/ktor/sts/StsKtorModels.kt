package io.bluetape4k.aws.ktor.sts

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

private const val ASSUME_ROLE_MIN_DURATION_SECONDS = 900
private const val ASSUME_ROLE_MAX_DURATION_SECONDS = 43_200
private const val SESSION_TOKEN_MIN_DURATION_SECONDS = 900
private const val SESSION_TOKEN_MAX_DURATION_SECONDS = 129_600

/**
 * STS를 통해 IAM 역할을 맡기 위한 요청입니다.
 *
 * ## 계약
 *
 * [durationSeconds]는 STS AssumeRole 표준 범위인 900~43,200초를 따릅니다.
 * [StsKtorOperations.assumeRole]은 응답을 AWS SDK 원본 객체로 반환합니다.
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
 * STS 세션 자격 증명을 얻기 위한 요청입니다.
 *
 * ## 계약
 *
 * [durationSeconds]는 STS GetSessionToken 표준 범위인 900~129,600초를 따릅니다.
 * MFA 필드는 선택 사항이지만 지정할 때는 비어 있지 않아야 합니다.
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
