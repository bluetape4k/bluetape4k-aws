package io.bluetape4k.aws.spring.parameterstore

import io.bluetape4k.aws.spring.config.AwsConfigDataResource
import io.bluetape4k.aws.spring.config.AwsConfigDataSource
import io.bluetape4k.aws.spring.config.AwsConfigDataSupport
import io.bluetape4k.aws.spring.config.applyConfigDataBootstrapCustomizer
import io.bluetape4k.aws.spring.config.configDataCredentialsProvider
import io.bluetape4k.aws.spring.applyAwsDefaults
import io.bluetape4k.aws.spring.resolveClientDefaults
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext
import software.amazon.awssdk.services.ssm.SsmClient

/** Parameter Store SDK type을 ConfigData SDK-free 경계 뒤에서만 참조하는 adapter입니다. */
internal object ParameterStoreConfigDataSdkAdapter {

    fun create(configuration: AwsConfigDataSupport.ResolverConfiguration): Any {
        val properties = configuration.backend as ParameterStoreProperties
        val defaults = configuration.aws.resolveClientDefaults(properties.region, properties.endpointOverride)
        return SsmClient.builder()
            .credentialsProvider(configuration.aws.configDataCredentialsProvider())
            .applyAwsDefaults(defaults)
            .applyConfigDataBootstrapCustomizer(configuration.bootstrapContext, "ssm")
            .build()
    }

    fun close(client: Any) {
        (client as SsmClient).close()
    }

    fun load(
        bootstrapContext: ConfigurableBootstrapContext,
        resource: AwsConfigDataResource,
    ): Map<String, Any> {
        val configuration = resource.boundProperties as AwsConfigDataSupport.ResolverConfiguration
        val source = resource.location.source as AwsConfigDataSource.ParameterStore
        val client = checkNotNull(bootstrapContext.get(SsmClient::class.java))
        return ParameterStorePropertySourceLoader.load(
            client,
            ParameterStoreProperties.Source(
                name = resource.opaqueIdentity,
                path = source.path,
                prefix = source.prefix,
                recursive = source.recursive,
                withDecryption = source.withDecryption,
                optional = resource.isOptionalResource,
            ),
        ).also {
            check((configuration.backend as ParameterStoreProperties).enabled) {
                "Parameter Store ConfigData backend is disabled."
            }
        }
    }
}
