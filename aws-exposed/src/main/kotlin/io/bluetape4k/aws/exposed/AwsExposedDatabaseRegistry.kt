package io.bluetape4k.aws.exposed

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank

/**
 * 기본 및 named Exposed database handle을 보관하는 registry입니다.
 */
class AwsExposedDatabaseRegistry(
    /** 기본 database handle입니다. */
    val defaultHandle: AwsExposedDatabaseHandle,
    /** 이름으로 조회할 수 있는 추가 database handle map입니다. */
    val namedHandles: Map<String, AwsExposedDatabaseHandle> = emptyMap(),
): AutoCloseable {

    companion object: KLogging()

    /**
     * database handle을 찾습니다. `null` 또는 기본 이름은 [defaultHandle]을 반환합니다.
     */
    operator fun get(name: String? = null): AwsExposedDatabaseHandle {
        if (name == null || name == AwsExposedDatabaseFactory.DEFAULT_DATABASE_NAME) {
            return defaultHandle
        }
        name.requireNotBlank("name")
        return namedHandles[name]
            ?: throw NoSuchElementException("No Exposed database registered for name '$name'.")
    }

    /**
     * named handle을 먼저 닫고 마지막에 기본 handle을 닫습니다.
     */
    override fun close() {
        var failure: Throwable? = null
        (namedHandles.values.toList().asReversed() + defaultHandle).forEach { handle ->
            try {
                handle.close()
            } catch (e: Throwable) {
                failure?.addSuppressed(e) ?: run { failure = e }
            }
        }
        failure?.let { throw IllegalStateException("Failed to close one or more Exposed database handles.", it) }
    }
}
