package io.bluetape4k.aws.spring.dynamodb

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

/**
 * DynamoDB 자동 설정 속성.
 */
@ConfigurationProperties(prefix = "bluetape4k.aws.dynamodb")
data class DynamoDbProperties(
    val enabled: Boolean = true,
    val region: String? = null,
    val endpointOverride: URI? = null,
    val tablePrefix: String = "",
) {
    init {
        require(endpointOverride == null || !region.isNullOrBlank()) {
            "bluetape4k.aws.dynamodb.region is required when endpointOverride is configured."
        }
    }
}
