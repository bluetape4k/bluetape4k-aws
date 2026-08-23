package io.bluetape4k.aws.spring.appconfig

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.AwsClientCustomizer
import io.bluetape4k.aws.spring.AwsProperties
import io.bluetape4k.aws.spring.AwsSyncClientCustomizer
import io.bluetape4k.aws.spring.ConditionalOnAwsEnabled
import io.bluetape4k.aws.spring.applyAwsDefaults
import io.bluetape4k.aws.spring.applyGlobalCustomizers
import io.bluetape4k.aws.spring.applyServiceCustomizers
import io.bluetape4k.aws.spring.resolveClientDefaults
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.env.ConfigurableEnvironment
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.services.appconfigdata.AppConfigDataClient
import software.amazon.awssdk.services.appconfigdata.AppConfigDataClientBuilder

/** AWS AppConfig Data client와 context 수명주기 poller의 자동 구성입니다. */
@AutoConfiguration(after = [AwsAutoConfiguration::class])
@ConditionalOnAwsEnabled
@ConditionalOnClass(name = ["software.amazon.awssdk.services.appconfigdata.AppConfigDataClient"])
@ConditionalOnProperty(
    prefix = "bluetape4k.aws.app-config",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(AppConfigProperties::class)
class AppConfigAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(AppConfigDataClient::class)
    fun appConfigDataClient(
        awsProperties: ObjectProvider<AwsProperties>,
        properties: AppConfigProperties,
        credentialsProvider: ObjectProvider<AwsCredentialsProvider>,
        globalCustomizers: ObjectProvider<AwsSyncClientCustomizer>,
        serviceCustomizers: ObjectProvider<AwsClientCustomizer<AppConfigDataClientBuilder>>,
    ): AppConfigDataClient =
        AppConfigDataClient.builder()
            .credentialsProvider(credentialsProvider.getIfAvailable { DefaultCredentialsProvider.builder().build() })
            .applyAwsDefaults(
                awsProperties.getIfAvailable { AwsProperties() }
                    .resolveClientDefaults(properties.region, properties.endpointOverride),
            )
            .also { it.applyGlobalCustomizers("appconfigdata", globalCustomizers) }
            .applyServiceCustomizers(serviceCustomizers)
            .build()

    @Bean
    @ConditionalOnMissingBean(AppConfigReloadLifecycle::class)
    fun appConfigReloadLifecycle(
        environment: ConfigurableEnvironment,
        appConfigDataClient: ObjectProvider<AppConfigDataClient>,
    ): org.springframework.context.SmartLifecycle =
        AppConfigReloadLifecycle(environment) {
            AppConfigDataSdkAdapter.sessionClient(appConfigDataClient.getObject())
        }
}
