package io.bluetape4k.aws.spring.appconfig

import io.bluetape4k.aws.spring.applyAwsDefaults
import io.bluetape4k.aws.spring.config.AwsConfigDataResource
import io.bluetape4k.aws.spring.config.AwsConfigDataSource
import io.bluetape4k.aws.spring.config.AwsConfigDataSupport
import io.bluetape4k.aws.spring.config.applyConfigDataBootstrapCustomizer
import io.bluetape4k.aws.spring.config.configDataCredentialsProvider
import io.bluetape4k.aws.spring.resolveClientDefaults
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext
import software.amazon.awssdk.services.appconfigdata.AppConfigDataClient
import software.amazon.awssdk.services.appconfigdata.model.GetLatestConfigurationRequest
import software.amazon.awssdk.services.appconfigdata.model.StartConfigurationSessionRequest

/** AWS SDK AppConfig Data 호출을 SDK-free 내부 계약으로 감쌉니다. */
internal object AppConfigDataSdkAdapter {

    fun create(configuration: AwsConfigDataSupport.ResolverConfiguration): Any {
        val properties = configuration.backend as AppConfigProperties
        val defaults = configuration.aws.resolveClientDefaults(properties.region, properties.endpointOverride)
        return AppConfigDataClient.builder()
            .credentialsProvider(configuration.aws.configDataCredentialsProvider())
            .applyAwsDefaults(defaults)
            .applyConfigDataBootstrapCustomizer(configuration.bootstrapContext, "appconfigdata")
            .build()
    }

    fun close(client: Any) {
        (client as AppConfigDataClient).close()
    }

    fun sessionClient(bootstrapContext: ConfigurableBootstrapContext): AppConfigDataSessionClient {
        val client = checkNotNull(bootstrapContext.get(AppConfigDataClient::class.java))
        return sessionClient(client)
    }

    fun sessionClient(client: AppConfigDataClient): AppConfigDataSessionClient =
        SdkAppConfigDataSessionClient(client)

    fun initialLoad(
        bootstrapContext: ConfigurableBootstrapContext,
        resource: AwsConfigDataResource,
    ): AppConfigDataInitialLoad {
        val configuration = resource.boundProperties as AwsConfigDataSupport.ResolverConfiguration
        val properties = configuration.backend as AppConfigProperties
        val source = resource.location.source as AwsConfigDataSource.AppConfig
        val client = sessionClient(bootstrapContext)
        val request = AppConfigDataStartRequest(
            applicationIdentifier = source.application,
            configurationProfileIdentifier = source.profile,
            environmentIdentifier = source.environment,
            requiredMinimumPollIntervalSeconds = properties.requiredMinimumPollInterval.seconds.toInt(),
        )
        val response = AppConfigDataSessionCursor(client, request).poll()
        val values = AppConfigDataDecoder.decode(
            payload = response.configuration,
            contentType = response.contentType,
            format = source.format,
            prefix = source.prefix,
        )
        return AppConfigDataInitialLoad(
            client = client,
            request = request,
            response = response,
            values = values,
            refreshInterval = properties.refreshInterval,
            requiredMinimumPollInterval = properties.requiredMinimumPollInterval,
            format = source.format,
            prefix = source.prefix,
        )
    }

    private class SdkAppConfigDataSessionClient(
        private val delegate: AppConfigDataClient,
    ) : AppConfigDataSessionClient {
        override fun startConfigurationSession(request: AppConfigDataStartRequest): AppConfigDataSession {
            val response = delegate.startConfigurationSession(
                StartConfigurationSessionRequest.builder()
                    .applicationIdentifier(request.applicationIdentifier)
                    .configurationProfileIdentifier(request.configurationProfileIdentifier)
                    .environmentIdentifier(request.environmentIdentifier)
                    .requiredMinimumPollIntervalInSeconds(request.requiredMinimumPollIntervalSeconds)
                    .build(),
            )
            return AppConfigDataSession(response.initialConfigurationToken())
        }

        override fun getLatestConfiguration(configurationToken: String): AppConfigDataResponse {
            require(configurationToken.isNotBlank()) { "AppConfig configuration token must not be blank." }
            val response = delegate.getLatestConfiguration(
                GetLatestConfigurationRequest.builder()
                    .configurationToken(configurationToken)
                    .build(),
            )
            return AppConfigDataResponse(
                nextPollConfigurationToken = response.nextPollConfigurationToken(),
                nextPollIntervalSeconds = response.nextPollIntervalInSeconds()?.toLong(),
                contentType = response.contentType(),
                configuration = response.configuration().asByteArray(),
            )
        }

        override fun close() {
            // Bootstrap 또는 application context owner가 실제 SDK client를 닫습니다.
        }
    }
}

internal data class AppConfigDataInitialLoad(
    val client: AppConfigDataSessionClient,
    val request: AppConfigDataStartRequest,
    val response: AppConfigDataResponse,
    val values: Map<String, Any>,
    val refreshInterval: java.time.Duration?,
    val requiredMinimumPollInterval: java.time.Duration,
    val format: AppConfigFormat,
    val prefix: String?,
) {
    override fun toString(): String =
        "AppConfigDataInitialLoad(values=${values.size}, response=$response, format=$format, " +
            "prefixConfigured=${!prefix.isNullOrBlank()}, refreshIntervalConfigured=${refreshInterval != null})"
}
