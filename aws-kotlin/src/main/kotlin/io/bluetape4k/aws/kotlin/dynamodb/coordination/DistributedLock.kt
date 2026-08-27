package io.bluetape4k.aws.kotlin.dynamodb.coordination

import kotlin.time.Duration

/**
 * fencing token을 발급하는 분산 lock SPI입니다.
 *
 * 구현체는 acquire/renew/heartbeat에서 경쟁으로 lease를 얻지 못한 경우 `null`을
 * 반환할 수 있습니다. caller가 duration을 명시하는 메서드가 SPI의 유일한 계약이며,
 * concrete adapter는 별도로 기본 duration 편의 overload를 제공할 수 있습니다.
 */
interface DistributedLock {

    /** 비어 있거나 만료된 key를 owner가 지정한 기간으로 획득합니다. */
    suspend fun tryAcquire(key: String, ownerId: String, leaseDuration: Duration): LockLease?

    /** 현재 lease의 owner·token·expiry가 일치할 때 lease를 연장합니다. */
    suspend fun renew(lease: LockLease, leaseDuration: Duration): LockLease?

    /** [renew]와 동일한 연장 동작을 명시적으로 표현하는 alias입니다. */
    suspend fun heartbeat(lease: LockLease, leaseDuration: Duration): LockLease?

    /** 현재 lease가 여전히 유효할 때 owner를 해제합니다. */
    suspend fun release(lease: LockLease): Boolean
}
