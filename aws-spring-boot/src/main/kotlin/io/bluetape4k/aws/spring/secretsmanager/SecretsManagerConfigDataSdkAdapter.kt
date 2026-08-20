package io.bluetape4k.aws.spring.secretsmanager

import io.bluetape4k.aws.spring.config.AwsConfigDataResource
import io.bluetape4k.aws.spring.config.AwsConfigDataSource
import io.bluetape4k.aws.spring.config.AwsConfigDataSupport
import io.bluetape4k.aws.spring.config.applyConfigDataBootstrapCustomizer
import io.bluetape4k.aws.spring.config.configDataCredentialsProvider
import io.bluetape4k.aws.spring.applyAwsDefaults
import io.bluetape4k.aws.spring.resolveClientDefaults
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient

/** Secrets Manager SDK type을 ConfigData SDK-free 경계 뒤에서만 참조하는 adapter입니다. */
internal object SecretsManagerConfigDataSdkAdapter {

    fun create(configuration: AwsConfigDataSupport.ResolverConfiguration): Any {
        val properties = configuration.backend as SecretsManagerProperties
        val defaults = configuration.aws.resolveClientDefaults(properties.region, properties.endpointOverride)
        return SecretsManagerClient.builder()
            .credentialsProvider(configuration.aws.configDataCredentialsProvider())
            .applyAwsDefaults(defaults)
            .applyConfigDataBootstrapCustomizer(configuration.bootstrapContext, "secretsmanager")
            .build()
    }

    fun close(client: Any) {
        (client as SecretsManagerClient).close()
    }

    fun load(
        bootstrapContext: ConfigurableBootstrapContext,
        resource: AwsConfigDataResource,
    ): Map<String, Any> {
        val configuration = resource.boundProperties as AwsConfigDataSupport.ResolverConfiguration
        val source = resource.location.source as AwsConfigDataSource.SecretsManager
        val client = checkNotNull(bootstrapContext.get(SecretsManagerClient::class.java))
        return SecretsManagerPropertySourceLoader.load(
            client,
            SecretsManagerProperties.Source(
                name = resource.opaqueIdentity,
                secretId = source.secretId,
                prefix = source.prefix,
                optional = resource.isOptionalResource,
                format = source.format,
            ),
        ).also {
            check((configuration.backend as SecretsManagerProperties).enabled) {
                "Secrets Manager ConfigData backend is disabled."
            }
        }
    }
}
