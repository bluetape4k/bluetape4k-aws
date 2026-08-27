package io.bluetape4k.aws.spring.sqs

import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable

/**
 * SQS 관찰 기능의 독립적인 활성화 속성입니다.
 *
 * 관찰 기능은 기본적으로 비활성화되며, `bluetape4k.aws.sqs.observation.enabled=true`일 때만
 * 후속 관찰 수명 주기에 참여합니다.
 */
@ConfigurationProperties(prefix = "bluetape4k.aws.sqs.observation")
data class SqsObservationProperties(
    val enabled: Boolean = false,
) : Serializable {

    companion object {
        @JvmField
        val serialVersionUID: Long = 1L
    }
}
