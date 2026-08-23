package io.bluetape4k.aws.spring.appconfig

import io.bluetape4k.aws.spring.config.AwsConfigDataBackend
import io.bluetape4k.aws.spring.config.AwsConfigDataFailurePolicy
import io.bluetape4k.aws.spring.config.AwsConfigDataLoadException
import io.bluetape4k.aws.spring.config.AwsConfigDataResource
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext
import org.springframework.boot.context.config.ConfigData
import org.springframework.boot.context.config.ConfigDataLoader
import org.springframework.boot.context.config.ConfigDataLoaderContext
import org.springframework.boot.context.config.ConfigDataResourceNotFoundException
import org.springframework.boot.logging.DeferredLogFactory

/** `aws-app-config:` 초기 응답을 dynamic property source로 노출합니다. */
@Suppress("TooGenericExceptionCaught", "ReturnCount")
class AppConfigDataLoader(
    @Suppress("UNUSED_PARAMETER") private val deferredLogFactory: DeferredLogFactory,
    private val bootstrapContext: ConfigurableBootstrapContext,
) : ConfigDataLoader<AwsConfigDataResource> {

    override fun isLoadable(context: ConfigDataLoaderContext, resource: AwsConfigDataResource): Boolean =
        resource.backendKey == AwsConfigDataBackend.APP_CONFIG.key

    override fun load(context: ConfigDataLoaderContext, resource: AwsConfigDataResource): ConfigData? {
        val configuration = resource.boundProperties
            as? io.bluetape4k.aws.spring.config.AwsConfigDataSupport.ResolverConfiguration
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
            if (!properties.failFast) return AwsConfigDataFailurePolicy.toConfigData(resource, emptyMap())
            throw AwsConfigDataLoadException(resource.backendKey, error::class.java.simpleName)
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
        return ConfigData(listOf(propertySource))
    }

    private companion object {
        const val APP_CONFIG_NOT_FOUND_EXCEPTION =
            "software.amazon.awssdk.services.appconfigdata.model.ResourceNotFoundException"
    }
}
