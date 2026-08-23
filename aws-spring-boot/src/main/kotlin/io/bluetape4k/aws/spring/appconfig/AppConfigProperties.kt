package io.bluetape4k.aws.spring.appconfig

import io.bluetape4k.aws.spring.env.requireRegionWhenEndpointOverride
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.net.URI
import java.time.Duration

/**
 * AWS AppConfig Data ConfigData와 선택적 runtime poller의 구성입니다.
 *
 * 기본값은 초기 ConfigData 로딩만 수행하며, [refreshInterval]을 지정했을 때만
 * 애플리케이션 context 수명 동안 원격 값을 다시 읽습니다.
 */
@ConfigurationProperties(prefix = "bluetape4k.aws.app-config")
data class AppConfigProperties(
    val enabled: Boolean = true,
    val region: String? = null,
    val endpointOverride: URI? = null,
    val failFast: Boolean = true,
    val separator: String = DEFAULT_SEPARATOR,
    val refreshInterval: Duration? = null,
    val requiredMinimumPollInterval: Duration = DEFAULT_REQUIRED_MINIMUM_POLL_INTERVAL,
): Serializable {

    init {
        requireRegionWhenEndpointOverride(endpointOverride, region, "bluetape4k.aws.app-config")
        require(separator.length == 1) { "bluetape4k.aws.app-config.separator must be one character." }
        require(separator[0] !in CONTROL_CHARACTERS && separator[0] !in "?%&=") {
            "bluetape4k.aws.app-config.separator must be a safe character."
        }
        validatePollInterval(requiredMinimumPollInterval, "requiredMinimumPollInterval")
        refreshInterval?.let { validatePollInterval(it, "refreshInterval") }
    }

    override fun toString(): String =
        "AppConfigProperties(enabled=$enabled, failFast=$failFast, separator='$separator', " +
            "refreshIntervalConfigured=${refreshInterval != null}, " +
            "requiredMinimumPollInterval=$requiredMinimumPollInterval, regionConfigured=${!region.isNullOrBlank()}, " +
            "endpointConfigured=${endpointOverride != null})"

    companion object {
        const val DEFAULT_SEPARATOR: String = "#"
        val DEFAULT_REQUIRED_MINIMUM_POLL_INTERVAL: Duration = Duration.ofSeconds(15)
        val MIN_POLL_INTERVAL: Duration = Duration.ofSeconds(15)
        val MAX_POLL_INTERVAL: Duration = Duration.ofHours(24)

        private val CONTROL_CHARACTERS: CharRange = '\u0000'..'\u001F'

        internal fun validatePollInterval(value: Duration, name: String) {
            require(value >= MIN_POLL_INTERVAL && value <= MAX_POLL_INTERVAL) {
                "$name must be between $MIN_POLL_INTERVAL and $MAX_POLL_INTERVAL."
            }
        }

        private const val serialVersionUID: Long = -7227108500640758653L
    }
}
