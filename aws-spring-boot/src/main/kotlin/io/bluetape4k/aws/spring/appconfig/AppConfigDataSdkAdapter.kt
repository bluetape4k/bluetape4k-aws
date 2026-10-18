package io.bluetape4k.aws.spring.appconfig

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.aws.spring.applyAwsDefaults
import io.bluetape4k.aws.spring.support.get
import io.bluetape4k.aws.spring.config.AwsConfigDataResource
import io.bluetape4k.aws.spring.config.AwsConfigDataSource
import io.bluetape4k.aws.spring.config.AwsConfigDataSupport
import io.bluetape4k.aws.spring.config.applyConfigDataBootstrapCustomizer
import io.bluetape4k.aws.spring.config.configDataCredentialsProvider
import io.bluetape4k.aws.spring.resolveClientDefaults
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.checkNotNull
import io.bluetape4k.support.requireNotBlank
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext
import software.amazon.awssdk.services.appconfigdata.AppConfigDataClient
import software.amazon.awssdk.services.appconfigdata.model.GetLatestConfigurationRequest
import software.amazon.awssdk.services.appconfigdata.model.StartConfigurationSessionRequest

/** AWS SDK AppConfig Data 호출을 SDK-free 내부 계약으로 감쌉니다. */
internal object AppConfigDataSdkAdapter: KLogging() {

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
        log.debug { "Closing ${client::class.simpleName}" }
        (client as? AppConfigDataClient)?.close()
    }

    fun sessionClient(bootstrapContext: ConfigurableBootstrapContext): AppConfigDataSessionClient {
        val client = bootstrapContext.get<AppConfigDataClient>().checkNotNull("client")
        log.debug { "Create AppConfigDataSessonClient. client=$client" }
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
    ): AppConfigDataSessionClient {
        override fun startConfigurationSession(request: AppConfigDataStartRequest): AppConfigDataSession {
            val request = StartConfigurationSessionRequest.builder()
                .applicationIdentifier(request.applicationIdentifier)
                .configurationProfileIdentifier(request.configurationProfileIdentifier)
                .environmentIdentifier(request.environmentIdentifier)
                .requiredMinimumPollIntervalInSeconds(request.requiredMinimumPollIntervalSeconds)
                .build()
            val response = delegate.startConfigurationSession(request)
            return AppConfigDataSession(response.initialConfigurationToken())
        }

        override fun getLatestConfiguration(configurationToken: String): AppConfigDataResponse {
            configurationToken.requireNotBlank("configurationToken")
            val request = GetLatestConfigurationRequest.builder()
                .configurationToken(configurationToken)
                .build()

            val response = delegate.getLatestConfiguration(request)

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
        ToStringBuilder(this)
            .add("values", values.size)
            .add("response", response)
            .add("format", format)
            .add("prefix", prefix)
            .add("refreshInterval", refreshInterval)
            .toString()
}
