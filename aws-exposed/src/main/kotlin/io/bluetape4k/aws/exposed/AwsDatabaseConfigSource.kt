package io.bluetape4k.aws.exposed

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
data class AwsDatabaseConfigSource(
    val type: AwsDatabaseConfigSourceType,
    val sourceId: String,
    val prefix: String? = null,
    val optional: Boolean = false,
): Serializable {

    init {
        sourceId.requireNotBlank("sourceId")
        prefix?.requireNotBlank("prefix")
    }

    companion object {
        private const val serialVersionUID: Long = 6680609014195938796L
    }
}
