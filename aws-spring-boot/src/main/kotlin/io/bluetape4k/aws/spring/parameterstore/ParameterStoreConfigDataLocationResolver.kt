package io.bluetape4k.aws.spring.parameterstore

import io.bluetape4k.aws.spring.config.AwsConfigDataBackend
import io.bluetape4k.aws.spring.config.AwsConfigDataResource
import io.bluetape4k.aws.spring.config.AwsConfigDataSupport
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext
import org.springframework.boot.context.config.ConfigDataLocation
import org.springframework.boot.context.config.ConfigDataLocationResolver
import org.springframework.boot.context.config.ConfigDataLocationResolverContext
import org.springframework.boot.context.config.Profiles
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.logging.DeferredLogFactory

/** `aws-parameterstore:` ConfigData location을 해석합니다. */
class ParameterStoreConfigDataLocationResolver(
    @Suppress("UNUSED_PARAMETER") private val deferredLogFactory: DeferredLogFactory,
    private val binder: Binder,
    private val bootstrapContext: ConfigurableBootstrapContext,
) : ConfigDataLocationResolver<AwsConfigDataResource> {

    override fun isResolvable(
        context: ConfigDataLocationResolverContext,
        location: ConfigDataLocation,
    ): Boolean = AwsConfigDataSupport.isResolvable(AwsConfigDataBackend.PARAMETER_STORE, location)

    override fun resolve(
        context: ConfigDataLocationResolverContext,
        location: ConfigDataLocation,
    ): List<AwsConfigDataResource> = listOf(
        AwsConfigDataSupport.resolve(
            binder = binder,
            bootstrapContext = bootstrapContext,
            location = location,
            backend = AwsConfigDataBackend.PARAMETER_STORE,
            clientClassName = "software.amazon.awssdk.services.ssm.SsmClient",
            dependency = "software.amazon.awssdk:ssm",
            clientSupplier = ParameterStoreConfigDataSdkAdapter::create,
            clientCloser = ParameterStoreConfigDataSdkAdapter::close,
        ),
    )

    override fun resolveProfileSpecific(
        context: ConfigDataLocationResolverContext,
        location: ConfigDataLocation,
        profiles: Profiles,
    ): List<AwsConfigDataResource> = emptyList()
}
