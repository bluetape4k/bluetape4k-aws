package io.bluetape4k.aws.spring.appconfig

/**
 * AWS SDK 타입을 ConfigData와 poller에서 분리하는 테스트 가능한 AppConfig Data 계약입니다.
 * token과 payload를 객체 문자열 표현에 포함하지 않습니다.
 */
internal interface AppConfigDataSessionClient : AutoCloseable {
    fun startConfigurationSession(request: AppConfigDataStartRequest): AppConfigDataSession

    fun getLatestConfiguration(configurationToken: String): AppConfigDataResponse

    override fun close()
}

internal data class AppConfigDataStartRequest(
    val applicationIdentifier: String,
    val configurationProfileIdentifier: String,
    val environmentIdentifier: String,
    val requiredMinimumPollIntervalSeconds: Int,
) {
    init {
        require(applicationIdentifier.isNotBlank()) { "AppConfig application identifier must not be blank." }
        require(configurationProfileIdentifier.isNotBlank()) {
            "AppConfig configuration profile identifier must not be blank."
        }
        require(environmentIdentifier.isNotBlank()) { "AppConfig environment identifier must not be blank." }
        require(requiredMinimumPollIntervalSeconds > 0) {
            "AppConfig required minimum poll interval must be positive."
        }
    }

    override fun toString(): String =
        "AppConfigDataStartRequest(applicationConfigured=true, profileConfigured=true, environmentConfigured=true, " +
            "requiredMinimumPollIntervalSeconds=$requiredMinimumPollIntervalSeconds)"
}

internal data class AppConfigDataSession(
    val initialConfigurationToken: String,
) {
    init {
        require(initialConfigurationToken.isNotBlank()) { "AppConfig initial configuration token must not be blank." }
    }

    override fun toString(): String = "AppConfigDataSession(initialConfigurationTokenPresent=true)"
}

internal data class AppConfigDataResponse(
    val nextPollConfigurationToken: String,
    val nextPollIntervalSeconds: Long?,
    val contentType: String?,
    val configuration: ByteArray,
) {
    init {
        require(nextPollConfigurationToken.isNotBlank()) {
            "AppConfig next poll configuration token must not be blank."
        }
        require(nextPollIntervalSeconds == null || nextPollIntervalSeconds >= 0) {
            "AppConfig next poll interval must not be negative when present."
        }
    }

    override fun equals(other: Any?): Boolean =
        other is AppConfigDataResponse &&
            nextPollConfigurationToken == other.nextPollConfigurationToken &&
            nextPollIntervalSeconds == other.nextPollIntervalSeconds &&
            contentType == other.contentType &&
            configuration.contentEquals(other.configuration)

    override fun hashCode(): Int {
        var result = nextPollConfigurationToken.hashCode()
        result = 31 * result + (nextPollIntervalSeconds?.hashCode() ?: 0)
        result = 31 * result + (contentType?.hashCode() ?: 0)
        result = 31 * result + configuration.contentHashCode()
        return result
    }

    override fun toString(): String =
        "AppConfigDataResponse(" +
            "nextPollConfigurationTokenPresent=true, " +
            "nextPollIntervalSeconds=$nextPollIntervalSeconds, " +
            "contentType=$contentType, configurationBytes=${configuration.size})"
}

/** response token을 매 호출 한 번만 소비하고 다음 token으로 교체하는 cursor입니다. */
internal class AppConfigDataSessionCursor(
    private val client: AppConfigDataSessionClient,
    request: AppConfigDataStartRequest,
) {
    private var currentToken: String = client.startConfigurationSession(request).initialConfigurationToken

    fun poll(): AppConfigDataResponse = client.getLatestConfiguration(currentToken).also {
        currentToken = it.nextPollConfigurationToken
    }

    fun discard() {
        currentToken = ""
    }
}
