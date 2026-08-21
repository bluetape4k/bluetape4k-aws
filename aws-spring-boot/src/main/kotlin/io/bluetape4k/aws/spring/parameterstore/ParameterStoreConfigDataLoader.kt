package io.bluetape4k.aws.spring.parameterstore

import io.bluetape4k.aws.spring.config.AwsConfigDataBackend
import io.bluetape4k.aws.spring.config.AwsConfigDataFailurePolicy
import io.bluetape4k.aws.spring.config.AwsConfigDataResource
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext
import org.springframework.boot.context.config.ConfigData
import org.springframework.boot.context.config.ConfigDataLoader
import org.springframework.boot.context.config.ConfigDataLoaderContext
import org.springframework.boot.logging.DeferredLogFactory

/** `aws-parameterstore:` ConfigData resource를 하나의 MapPropertySource로 로드합니다. */
class ParameterStoreConfigDataLoader(
    @Suppress("UNUSED_PARAMETER") private val deferredLogFactory: DeferredLogFactory,
    private val bootstrapContext: ConfigurableBootstrapContext,
) : ConfigDataLoader<AwsConfigDataResource> {

    override fun isLoadable(context: ConfigDataLoaderContext, resource: AwsConfigDataResource): Boolean =
        resource.backendKey == AwsConfigDataBackend.PARAMETER_STORE.key

    override fun load(context: ConfigDataLoaderContext, resource: AwsConfigDataResource): ConfigData? {
        if (resource.isDisabled) {
            return AwsConfigDataFailurePolicy.toConfigData(resource, emptyMap())
        }
        return AwsConfigDataFailurePolicy.load(resource) {
            ParameterStoreConfigDataSdkAdapter.load(bootstrapContext, resource)
        }?.let { values -> AwsConfigDataFailurePolicy.toConfigData(resource, values) }
    }
}
