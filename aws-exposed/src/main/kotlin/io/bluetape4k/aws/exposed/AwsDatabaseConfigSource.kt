package io.bluetape4k.aws.exposed

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * database 설정을 가져올 remote configuration backend 유형입니다.
 */
enum class AwsDatabaseConfigSourceType {
    /**
     * AWS Secrets Manager secret payload입니다.
     */
    SECRETS_MANAGER,

    /**
     * AWS Systems Manager Parameter Store path 또는 parameter set입니다.
     */
    PARAMETER_STORE,
}

/**
 * remote database configuration source를 표현하는 storage-neutral descriptor입니다.
 *
 * 이 module은 AWS 값을 직접 가져오지 않습니다. Spring Boot와 Ktor adapter가 이 descriptor를 사용해 자체
 * AWS client로 설정을 해석하고, 해석된 값을 [AwsDatabaseSettingsResolver]로 다시 전달합니다.
 */
@ConsistentCopyVisibility
data class AwsDatabaseConfigSource private constructor(
    /** 설정을 가져올 remote backend 유형입니다. */
    val type: AwsDatabaseConfigSourceType,
    /** secret id, parameter path 등 backend에서 source를 식별하는 값입니다. */
    val sourceId: String,
    /** source 안에서 database 설정 key를 구분할 선택적 prefix입니다. */
    val prefix: String? = null,
    /** source가 없거나 비어 있어도 오류로 처리하지 않을지 나타냅니다. */
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
