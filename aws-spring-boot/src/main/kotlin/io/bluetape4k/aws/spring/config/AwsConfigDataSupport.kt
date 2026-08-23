package io.bluetape4k.aws.spring.config

import io.bluetape4k.aws.spring.AwsProperties
import io.bluetape4k.aws.spring.appconfig.AppConfigProperties
import io.bluetape4k.aws.spring.parameterstore.ParameterStoreProperties
import io.bluetape4k.aws.spring.s3.S3ConfigProperties
import io.bluetape4k.aws.spring.secretsmanager.SecretsManagerProperties
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext
import org.springframework.boot.context.config.ConfigDataLocation
import org.springframework.boot.context.config.ConfigDataLocationResolverContext
import org.springframework.boot.context.properties.bind.Binder

/** Resolver가 공유하는 Binder, guard, bootstrap 등록 경계입니다. */
internal object AwsConfigDataSupport {

    data class ResolverConfiguration(
        val aws: AwsProperties,
        val backend: Any,
        val bootstrapContext: ConfigurableBootstrapContext,
    )

    fun resolve(
        binder: Binder,
        bootstrapContext: ConfigurableBootstrapContext,
        location: ConfigDataLocation,
        backend: AwsConfigDataBackend,
        clientClassName: String,
        dependency: String,
        clientSupplier: (ResolverConfiguration) -> Any = {
            error("ConfigData client adapter is not initialized.")
        },
        clientCloser: (Any) -> Unit = { },
    ): AwsConfigDataResource {
        val configuration = bindConfiguration(binder, bootstrapContext, backend)
        val separator = (configuration.backend as? AppConfigProperties)?.separator ?: "#"
        val parsed = AwsConfigDataLocationParser().parse(location, separator)
        check(parsed.backend == backend) { "ConfigData backend does not match resolver." }
        val disabled = !configuration.aws.enabled || !backendEnabled(configuration.backend)
        if (!disabled) {
            AwsConfigDataBootstrapBridge.requireClass(clientClassName, dependency)
            AwsConfigDataBootstrapBridge.registerClient(
                bootstrapContext = bootstrapContext,
                clientClassName = clientClassName,
                dependency = dependency,
                supplier = { clientSupplier(configuration) },
                closer = clientCloser,
            )
        }
        return AwsConfigDataResource.from(parsed, configuration, disabled)
    }

    fun isResolvable(backend: AwsConfigDataBackend, location: ConfigDataLocation): Boolean =
        location.hasPrefix(backend.prefix)

    fun resolve(
        context: ConfigDataLocationResolverContext,
        location: ConfigDataLocation,
        backend: AwsConfigDataBackend,
        clientClassName: String,
        dependency: String,
    ): AwsConfigDataResource = resolve(
        binder = context.binder,
        bootstrapContext = context.bootstrapContext,
        location = location,
        backend = backend,
        clientClassName = clientClassName,
        dependency = dependency,
    )

    private fun bindConfiguration(
        binder: Binder,
        bootstrapContext: ConfigurableBootstrapContext,
        backend: AwsConfigDataBackend,
    ): ResolverConfiguration {
        val aws = binder.bindOrCreate("bluetape4k.aws", AwsProperties::class.java)
        val backendProperties = when (backend) {
            AwsConfigDataBackend.S3 ->
                binder.bindOrCreate("bluetape4k.aws.s3.config", S3ConfigProperties::class.java)

            AwsConfigDataBackend.PARAMETER_STORE ->
                binder.bindOrCreate("bluetape4k.aws.parameter-store", ParameterStoreProperties::class.java)

            AwsConfigDataBackend.SECRETS_MANAGER ->
                binder.bindOrCreate("bluetape4k.aws.secrets-manager", SecretsManagerProperties::class.java)

            AwsConfigDataBackend.APP_CONFIG ->
                binder.bindOrCreate("bluetape4k.aws.app-config", AppConfigProperties::class.java)
        }
        return ResolverConfiguration(aws, backendProperties, bootstrapContext)
    }

    private fun backendEnabled(properties: Any): Boolean = when (properties) {
        is S3ConfigProperties -> properties.enabled
        is ParameterStoreProperties -> properties.enabled
        is SecretsManagerProperties -> properties.enabled
        is AppConfigProperties -> properties.enabled
        else -> error("Unsupported AWS ConfigData backend properties.")
    }
}
