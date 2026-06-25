package io.bluetape4k.aws.exposed

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank

/**
 * Registry for default and named Exposed database handles.
 */
class AwsExposedDatabaseRegistry(
    val defaultHandle: AwsExposedDatabaseHandle,
    val namedHandles: Map<String, AwsExposedDatabaseHandle> = emptyMap(),
): AutoCloseable {

    companion object: KLogging()

    /**
     * Finds a database handle. A null or default name returns [defaultHandle].
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
     * Closes named handles first, then the default handle.
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
