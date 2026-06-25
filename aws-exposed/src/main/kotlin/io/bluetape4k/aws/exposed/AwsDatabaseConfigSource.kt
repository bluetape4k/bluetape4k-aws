package io.bluetape4k.aws.exposed

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * Remote configuration backend type for database settings.
 */
enum class AwsDatabaseConfigSourceType {
    /**
     * AWS Secrets Manager secret payload.
     */
    SECRETS_MANAGER,

    /**
     * AWS Systems Manager Parameter Store path or parameter set.
     */
    PARAMETER_STORE,
}

/**
 * Storage-neutral descriptor for a remote database configuration source.
 *
 * This module does not fetch AWS values directly. Spring Boot and Ktor adapters
 * use this descriptor to resolve settings through their own AWS clients and pass
 * the resolved values back to [AwsDatabaseSettingsResolver].
 */
@ConsistentCopyVisibility
data class AwsDatabaseConfigSource private constructor(
    val type: AwsDatabaseConfigSourceType,
    val sourceId: String,
    val prefix: String? = null,
    val optional: Boolean = false,
): Serializable {

    companion object: KLogging() {
        private const val serialVersionUID: Long = 6680609014195938796L

        operator fun invoke(
            type: AwsDatabaseConfigSourceType,
            sourceId: String,
            prefix: String? = null,
            optional: Boolean = false,
        ): AwsDatabaseConfigSource {
            sourceId.requireNotBlank("sourceId")
            prefix?.requireNotBlank("prefix")
            return AwsDatabaseConfigSource(type, sourceId, prefix, optional)
        }
    }
}
