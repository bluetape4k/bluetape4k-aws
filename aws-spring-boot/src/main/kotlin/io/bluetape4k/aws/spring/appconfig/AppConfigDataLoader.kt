package io.bluetape4k.aws.spring.appconfig

import io.bluetape4k.aws.spring.config.AwsConfigDataBackend
import io.bluetape4k.aws.spring.config.AwsConfigDataFailurePolicy
import io.bluetape4k.aws.spring.config.AwsConfigDataLoadException
import io.bluetape4k.aws.spring.config.AwsConfigDataResource
import io.bluetape4k.aws.spring.config.AwsConfigDataSupport
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext
import org.springframework.boot.context.config.ConfigData
import org.springframework.boot.context.config.ConfigDataLoader
import org.springframework.boot.context.config.ConfigDataLoaderContext
import org.springframework.boot.context.config.ConfigDataResourceNotFoundException
import org.springframework.boot.logging.DeferredLogFactory

/** `aws-app-config:` 초기 응답을 dynamic property source로 노출합니다. */
@Suppress("TooGenericExceptionCaught", "ReturnCount")
class AppConfigDataLoader(
    @Suppress("UNUSED_PARAMETER")
    private val deferredLogFactory: DeferredLogFactory,
    private val bootstrapContext: ConfigurableBootstrapContext,
): ConfigDataLoader<AwsConfigDataResource> {

    private companion object: KLogging() {
        const val APP_CONFIG_NOT_FOUND_EXCEPTION =
            "software.amazon.awssdk.services.appconfigdata.model.ResourceNotFoundException"
    }

    override fun isLoadable(context: ConfigDataLoaderContext, resource: AwsConfigDataResource): Boolean =
        resource.backendKey == AwsConfigDataBackend.APP_CONFIG.key

    override fun load(context: ConfigDataLoaderContext, resource: AwsConfigDataResource): ConfigData? {
        val configuration = resource.boundProperties
                as? AwsConfigDataSupport.ResolverConfiguration
            ?: error("AWS AppConfig ConfigData properties are missing.")

        val properties = configuration.backend as AppConfigProperties
        if (resource.isDisabled) {
            return AwsConfigDataFailurePolicy.toConfigData(resource, emptyMap())
        }

        val initial = try {
            AppConfigDataSdkAdapter.initialLoad(bootstrapContext, resource)
        } catch (error: RuntimeException) {
            val notFound = error::class.java.name == APP_CONFIG_NOT_FOUND_EXCEPTION
            if (notFound) {
                if (resource.isOptionalResource) return null
                if (!properties.failFast) return AwsConfigDataFailurePolicy.toConfigData(resource, emptyMap())
                throw ConfigDataResourceNotFoundException(resource)
            }
            if (!properties.failFast) {
                return AwsConfigDataFailurePolicy.toConfigData(resource, emptyMap())
            }
            val errorType = error::class.java.simpleName.ifBlank { error::class.java.name }
            throw AwsConfigDataLoadException(resource.backendKey, errorType)
        }
        val propertySource = AppConfigDataPropertySource(
            name = resource.toString(),
            initialValues = initial.values,
            resource = resource,
            client = initial.client,
            request = initial.request,
            initialResponse = initial.response,
            format = initial.format,
            prefix = initial.prefix,
            refreshInterval = initial.refreshInterval,
            requiredMinimumPollInterval = initial.requiredMinimumPollInterval,
        )
        log.debug { "Loading property source from $propertySource" }
        return ConfigData(listOf(propertySource))
    }
}
