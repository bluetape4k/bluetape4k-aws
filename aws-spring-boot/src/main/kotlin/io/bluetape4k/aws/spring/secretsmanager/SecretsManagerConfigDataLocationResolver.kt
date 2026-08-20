package io.bluetape4k.aws.spring.secretsmanager

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

/** `aws-secretsmanager:` ConfigData location을 해석합니다. */
class SecretsManagerConfigDataLocationResolver(
    @Suppress("UNUSED_PARAMETER") private val deferredLogFactory: DeferredLogFactory,
    private val binder: Binder,
    private val bootstrapContext: ConfigurableBootstrapContext,
) : ConfigDataLocationResolver<AwsConfigDataResource> {

    override fun isResolvable(
        context: ConfigDataLocationResolverContext,
        location: ConfigDataLocation,
    ): Boolean = AwsConfigDataSupport.isResolvable(AwsConfigDataBackend.SECRETS_MANAGER, location)

    override fun resolve(
        context: ConfigDataLocationResolverContext,
        location: ConfigDataLocation,
    ): List<AwsConfigDataResource> = listOf(
        AwsConfigDataSupport.resolve(
            binder = binder,
            bootstrapContext = bootstrapContext,
            location = location,
            backend = AwsConfigDataBackend.SECRETS_MANAGER,
            clientClassName = "software.amazon.awssdk.services.secretsmanager.SecretsManagerClient",
            dependency = "software.amazon.awssdk:secretsmanager",
            clientSupplier = SecretsManagerConfigDataSdkAdapter::create,
            clientCloser = SecretsManagerConfigDataSdkAdapter::close,
        ),
    )

    override fun resolveProfileSpecific(
        context: ConfigDataLocationResolverContext,
        location: ConfigDataLocation,
        profiles: Profiles,
    ): List<AwsConfigDataResource> = emptyList()
}
