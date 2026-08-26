package io.bluetape4k.aws.spring.modulith

import java.time.Duration
import java.time.Instant

/** 외부 event의 type과 식별자로 구성된 idempotency key입니다. */
data class AwsModulithEventKey(
    val type: String,
    val eventId: String,
)

/** lease 소유권과 stale mutation을 구분하는 fencing token입니다. */
data class AwsModulithClaimToken(
    val key: AwsModulithEventKey,
    val ownerId: String,
    val generation: Long,
    val leaseUntil: Instant,
)

/** claim 시점의 atomic 상태입니다. */
sealed interface AwsModulithClaimResult {
    data class Acquired(val token: AwsModulithClaimToken) : AwsModulithClaimResult

    data object Completed : AwsModulithClaimResult

    data class InProgress(val leaseUntil: Instant) : AwsModulithClaimResult
}

/** complete와 release mutation의 bounded 결과입니다. */
enum class AwsModulithStoreMutation {
    APPLIED,
    ALREADY_APPLIED,
    NOT_FOUND,
    STALE,
}

/**
 * inbound event의 lease와 fencing을 관리하는 idempotency store 계약입니다.
 *
 * 구현은 key별 claim을 선형화하고 blocking I/O가 필요하면 구현 내부에서 별도 dispatcher로
 * 격리해야 합니다. library는 사용자 구현의 lifecycle을 소유하지 않습니다.
 */
interface AwsModulithEventIdempotencyStore {
    suspend fun claim(key: AwsModulithEventKey, leaseDuration: Duration): AwsModulithClaimResult

    suspend fun renew(token: AwsModulithClaimToken, leaseDuration: Duration): AwsModulithClaimToken

    suspend fun complete(token: AwsModulithClaimToken): AwsModulithStoreMutation

    suspend fun release(token: AwsModulithClaimToken): AwsModulithStoreMutation

    suspend fun recoverExpired(now: Instant): Int
}
