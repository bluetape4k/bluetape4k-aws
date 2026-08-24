package io.bluetape4k.aws.spring.connection

import java.util.Collections

/**
 * AWS ServiceConnection 설정을 안전하게 중단할 때 사용하는 예외입니다.
 *
 * [message]와 공개 필드는 서비스 이름, 오류 이유, 후보 개수만 포함합니다.
 * 자격 증명과 endpoint 및 원본 예외의 메시지·스택트레이스는 보존하지 않습니다.
 */
class AwsServiceConnectionConfigurationException(
    val reason: Reason,
    serviceNames: Set<String>,
    val candidateCount: Int,
    causeSummary: String? = null,
): IllegalStateException(buildMessage(reason, serviceNames, candidateCount, causeSummary)) {

    /** ServiceConnection 설정 실패의 안정적인 분류입니다. */
    enum class Reason {
        FACTORY_LINKAGE,
        DUPLICATE_DETAILS,
        CREDENTIAL_CONFLICT,
        MALFORMED_DETAILS,
    }

    val serviceNames: Set<String> = Collections.unmodifiableSet(serviceNames.toSortedSet())

    init {
        require(candidateCount >= 0) { "candidateCount must not be negative" }
    }

    companion object {
        private val CLASS_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")

        private fun buildMessage(
            reason: Reason,
            serviceNames: Set<String>,
            candidateCount: Int,
            causeSummary: String?,
        ): String {
            val services = serviceNames.toSortedSet().joinToString(",")
            val safeCause = causeSummary
                ?.takeIf { CLASS_NAME.matches(it) }
                ?.substringAfterLast('.')
            return buildString {
                append("AWS ServiceConnection configuration failed: reason=")
                append(reason)
                append(", services=[")
                append(services)
                append("], candidates=")
                append(candidateCount)
                safeCause?.let {
                    append(", cause=")
                    append(it)
                }
            }
        }
    }
}
