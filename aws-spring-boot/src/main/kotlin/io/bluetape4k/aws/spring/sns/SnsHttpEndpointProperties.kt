package io.bluetape4k.aws.spring.sns

import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable

internal const val SNS_HTTP_ENDPOINTS_PROPERTIES_PREFIX = "bluetape4k.aws.sns.http-endpoints"

/** SNS HTTP endpoint adapter의 보안·lifecycle 정책입니다. */
@ConfigurationProperties(prefix = SNS_HTTP_ENDPOINTS_PROPERTIES_PREFIX)
data class SnsHttpEndpointProperties(
    val enabled: Boolean = true,
    val verificationRequired: Boolean = true,
    val allowStructuralOnly: Boolean = false,
    val expectedTopicArns: Set<String> = emptySet(),
) : Serializable {

    init {
        require(!(verificationRequired && allowStructuralOnly)) {
            "$SNS_HTTP_ENDPOINTS_PROPERTIES_PREFIX.verification-required and " +
                "$SNS_HTTP_ENDPOINTS_PROPERTIES_PREFIX.allow-structural-only cannot both be true."
        }
        require(verificationRequired || allowStructuralOnly) {
            "$SNS_HTTP_ENDPOINTS_PROPERTIES_PREFIX must enable verification-required or " +
                "allow-structural-only."
        }
        require(expectedTopicArns.none { it.isBlank() }) {
            "$SNS_HTTP_ENDPOINTS_PROPERTIES_PREFIX.expected-topic-arns must not contain blank values."
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
