package io.bluetape4k.aws.spring.appconfig

import io.bluetape4k.aws.spring.config.AwsConfigDataResource
import org.springframework.core.env.EnumerablePropertySource
import java.time.Duration

/** AppConfig 값을 atomic immutable map으로 노출하는 ConfigData property source입니다. */
internal class AppConfigDataPropertySource(
    name: String,
    initialValues: Map<String, Any>,
    val resource: AwsConfigDataResource,
    client: AppConfigDataSessionClient,
    val request: AppConfigDataStartRequest,
    initialResponse: AppConfigDataResponse,
    val format: AppConfigFormat,
    val prefix: String?,
    val refreshInterval: Duration?,
    val requiredMinimumPollInterval: Duration,
) : EnumerablePropertySource<Map<String, Any>>(name, initialValues.toMap()) {

    @Volatile
    var client: AppConfigDataSessionClient = client
        private set

    @Volatile
    private var currentValues: Map<String, Any> = initialValues.toMap()

    @Volatile
    var configurationToken: String? = initialResponse.nextPollConfigurationToken
        private set

    @Volatile
    var nextPollIntervalSeconds: Long? = initialResponse.nextPollIntervalSeconds
        private set

    @Volatile
    var sessionActive: Boolean = true
        private set

    override fun getProperty(name: String): Any? = currentValues[name]

    override fun containsProperty(name: String): Boolean = currentValues.containsKey(name)

    override fun getPropertyNames(): Array<String> = currentValues.keys.toTypedArray()

    fun replace(values: Map<String, Any>) {
        currentValues = values.toMap()
    }

    fun valuesSnapshot(): Map<String, Any> = currentValues.toMap()

    fun replaceClient(client: AppConfigDataSessionClient) {
        this.client = client
    }

    fun advance(response: AppConfigDataResponse) {
        configurationToken = response.nextPollConfigurationToken
        nextPollIntervalSeconds = response.nextPollIntervalSeconds
        sessionActive = true
    }

    fun discardSession() {
        configurationToken = null
        nextPollIntervalSeconds = null
        sessionActive = false
    }

    fun activateSession(session: AppConfigDataSession) {
        configurationToken = session.initialConfigurationToken
        nextPollIntervalSeconds = null
        sessionActive = true
    }

    override fun toString(): String = "AppConfigDataPropertySource(name=${opaqueName(name)})"

    private fun opaqueName(value: String): String =
        value.substringAfterLast('.').takeIf { it.length <= OPAQUE_NAME_MAX_LENGTH } ?: "opaque"

    private companion object {
        const val OPAQUE_NAME_MAX_LENGTH = 64
    }
}
