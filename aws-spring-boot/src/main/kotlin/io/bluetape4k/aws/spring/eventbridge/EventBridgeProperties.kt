package io.bluetape4k.aws.spring.eventbridge

import io.bluetape4k.support.requireNotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.net.URI

/**
 * AWS EventBridge 통합용 Spring 구성 속성입니다.
 *
 * ## 계약
 *
 * 이 값은 EventBridge 클라이언트에 대해서만 공유 AWS 기본값보다 우선합니다. 호출자가
 * 이벤트 버스 이름을 생략하면 [defaultEventBusName]을 규칙, 대상, 목록 작업에 적용하며
 * `PutEvents` 항목은 자체 버스 값을 유지합니다.
 */
@ConfigurationProperties(prefix = "bluetape4k.aws.eventbridge")
data class EventBridgeProperties(
    val enabled: Boolean = true,
    val region: String? = null,
    val endpointOverride: URI? = null,
    val defaultEventBusName: String? = null,
) : Serializable {

    init {
        defaultEventBusName?.requireNotBlank("defaultEventBusName")
    }

    companion object {
        private const val serialVersionUID: Long = 5987147032763532105L
    }
}
