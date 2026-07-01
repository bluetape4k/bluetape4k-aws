package io.bluetape4k.aws.spring.eventbridge

import io.bluetape4k.support.requireNotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.net.URI

/**
 * Spring configuration properties for AWS EventBridge integration.
 *
 * ## Contract
 *
 * Values here override shared AWS defaults for the EventBridge client only.
 * [defaultEventBusName] is applied to rule, target, and list operations when
 * callers omit an event bus name; `PutEvents` entries keep their own bus value.
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
