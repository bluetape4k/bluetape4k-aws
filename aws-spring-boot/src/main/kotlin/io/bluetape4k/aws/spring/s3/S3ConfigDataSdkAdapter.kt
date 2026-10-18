package io.bluetape4k.aws.spring.s3

import io.bluetape4k.aws.spring.applyAwsDefaults
import io.bluetape4k.aws.spring.config.AwsConfigDataResource
import io.bluetape4k.aws.spring.config.AwsConfigDataSource
import io.bluetape4k.aws.spring.config.AwsConfigDataSupport
import io.bluetape4k.aws.spring.config.applyConfigDataBootstrapCustomizer
import io.bluetape4k.aws.spring.config.configDataCredentialsProvider
import io.bluetape4k.aws.spring.resolveClientDefaults
import io.bluetape4k.aws.spring.support.get
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.checkBeTrue
import io.bluetape4k.support.checkNotNull
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext
import software.amazon.awssdk.services.s3.S3Client

/** S3 SDK type을 ConfigData SDK-free 경계 뒤에서만 참조하는 adapter입니다. */
internal object S3ConfigDataSdkAdapter: KLogging() {

    fun create(configuration: AwsConfigDataSupport.ResolverConfiguration): Any {
        val properties = configuration.backend as S3ConfigProperties
        val defaults = configuration.aws.resolveClientDefaults(properties.region, properties.endpointOverride)

        return S3Client.builder()
            .credentialsProvider(configuration.aws.configDataCredentialsProvider())
            .applyAwsDefaults(defaults)
            .apply {
                serviceConfiguration { it.pathStyleAccessEnabled(properties.pathStyleAccessEnabled) }
            }
            .applyConfigDataBootstrapCustomizer(configuration.bootstrapContext, "s3")
            .build()
    }

    fun close(client: Any) {
        (client as S3Client).close()
    }

    fun load(
        bootstrapContext: ConfigurableBootstrapContext,
        resource: AwsConfigDataResource,
    ): Map<String, Any> {
        val configuration = resource.boundProperties as AwsConfigDataSupport.ResolverConfiguration
        val source = resource.location.source as AwsConfigDataSource.S3
        val properties = configuration.backend as S3ConfigProperties
        val client = bootstrapContext.get<S3Client>().checkNotNull("S3Client")

        return S3ConfigPropertySourceLoader.load(
            client,
            S3ConfigProperties.Source(
                name = resource.opaqueIdentity,
                bucket = source.bucket,
                key = source.key,
                prefix = source.prefix,
                format = source.format,
                optional = resource.isOptionalResource,
            ),
        ).also {
            properties.enabled.checkBeTrue { "S3 ConfigData backend is disabled." }
        }
    }
}
