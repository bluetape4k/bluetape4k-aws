package io.bluetape4k.aws.kotlin.dynamodb.coordination

import kotlin.time.Duration

/**
 * DynamoDB coordination table에서 bounded String metadata를 관리하는 SPI입니다.
 *
 * codec과 payload schema는 caller가 소유합니다. `ttl`은 logical expiry와 metadata
 * 전용 DynamoDB TTL attribute 기록에 함께 사용되는 선택적 기간입니다.
 */
interface MetadataStore {

    /** 만료되지 않은 metadata를 읽고 논리적으로 만료된 값은 `null`로 반환합니다. */
    suspend fun get(key: String): String?

    /** metadata를 명시적으로 overwrite합니다. `ttl == null`이면 만료 attribute를 제거합니다. */
    suspend fun put(key: String, value: String, ttl: Duration? = null)

    /** key가 비어 있거나 논리적으로 만료된 경우에만 metadata를 기록합니다. */
    suspend fun putIfAbsent(key: String, value: String, ttl: Duration? = null): Boolean

    /** metadata item을 조건부로 제거합니다. */
    suspend fun remove(key: String): Boolean

    /** expected value와 일치하는 metadata item만 조건부로 제거합니다. */
    suspend fun removeIfValue(key: String, expectedValue: String): Boolean
}
