package io.bluetape4k.aws.spring.appconfig

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.support.hashOf
import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable

/**
 * AWS SDK 타입을 ConfigData와 poller에서 분리하는 테스트 가능한 AppConfig Data 계약입니다.
 * token과 payload를 객체 문자열 표현에 포함하지 않습니다.
 */
internal interface AppConfigDataSessionClient: AutoCloseable {
    fun startConfigurationSession(request: AppConfigDataStartRequest): AppConfigDataSession

    fun getLatestConfiguration(configurationToken: String): AppConfigDataResponse

    override fun close()
}

internal data class AppConfigDataStartRequest(
    val applicationIdentifier: String,
    val configurationProfileIdentifier: String,
    val environmentIdentifier: String,
    val requiredMinimumPollIntervalSeconds: Int,
): Serializable {
    init {
        applicationIdentifier.requireNotBlank { "AppConfig application identifier must not be blank." }
        configurationProfileIdentifier.requireNotBlank { "AppConfig configuration profile identifier must not be blank." }
        environmentIdentifier.requireNotBlank { "AppConfig environment identifier must not be blank." }
        requiredMinimumPollIntervalSeconds.requirePositiveNumber { "AppConfig required minimum poll interval must be positive." }
    }
}

internal data class AppConfigDataSession(
    val initialConfigurationToken: String,
) {
    init {
        initialConfigurationToken.requireNotBlank { "AppConfig initial configuration token must not be blank." }
    }
}

internal data class AppConfigDataResponse(
    val nextPollConfigurationToken: String,
    val nextPollIntervalSeconds: Long?,
    val contentType: String?,
    val configuration: ByteArray,
) {
    init {
        nextPollConfigurationToken.requireNotBlank { "AppConfig next poll configuration token must not be blank." }
        nextPollIntervalSeconds?.requireGe(0) {
            "AppConfig next poll interval must not be negative when present."
        }
    }

    override fun equals(other: Any?): Boolean =
        other is AppConfigDataResponse &&
                nextPollConfigurationToken == other.nextPollConfigurationToken &&
                nextPollIntervalSeconds == other.nextPollIntervalSeconds &&
                contentType == other.contentType &&
                configuration.contentEquals(other.configuration)

    override fun hashCode(): Int =
        hashOf(nextPollConfigurationToken, nextPollIntervalSeconds, contentType, configuration.contentHashCode())

    override fun toString(): String =
        ToStringBuilder(this)
            .add("nextPollConfigurationToken", nextPollConfigurationToken.isNotBlank())
            .add("nextPollIntervalSeconds", nextPollIntervalSeconds)
            .add("contentType", contentType)
            .add("configurationBytes", configuration.size)
            .toString()
}

/** response token을 매 호출 한 번만 소비하고 다음 token으로 교체하는 cursor입니다. */
internal class AppConfigDataSessionCursor(
    private val client: AppConfigDataSessionClient,
    request: AppConfigDataStartRequest,
) {
    private var currentToken: String =
        client.startConfigurationSession(request).initialConfigurationToken

    fun poll(): AppConfigDataResponse =
        client.getLatestConfiguration(currentToken)
            .also {
                currentToken = it.nextPollConfigurationToken
            }

    fun discard() {
        currentToken = ""
    }
}
