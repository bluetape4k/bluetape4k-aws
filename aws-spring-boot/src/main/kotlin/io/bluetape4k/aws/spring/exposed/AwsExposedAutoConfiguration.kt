package io.bluetape4k.aws.spring.exposed

import io.bluetape4k.aws.exposed.AwsDatabaseSettingsResolver
import io.bluetape4k.aws.exposed.AwsExposedDatabaseFactory
import io.bluetape4k.aws.exposed.AwsExposedDatabaseHandle
import io.bluetape4k.aws.exposed.AwsExposedDatabaseRegistry
import io.bluetape4k.aws.spring.AwsAutoConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata
import javax.sql.DataSource
import io.bluetape4k.aws.spring.ConditionalOnAwsEnabled

/**
 * AWS 기반 Exposed database registry를 구성하는 Spring Boot auto-configuration입니다.
 *
 * ## 계약
 *
 * 기본 database URL 또는 source descriptor가 설정된 경우에만 공통 `bluetape4k-aws-exposed` registry를 생성합니다.
 * Secrets Manager와 Parameter Store integration은 registry 생성 전에 property를 게시하는 기존 environment
 * post-processor가 제공합니다.
 */
@AutoConfiguration(after = [AwsAutoConfiguration::class])
@ConditionalOnAwsEnabled
@ConditionalOnClass(
    name = [
        "io.bluetape4k.aws.exposed.AwsExposedDatabaseFactory",
        "io.bluetape4k.aws.exposed.AwsExposedDatabaseRegistry",
        "org.jetbrains.exposed.v1.jdbc.Database",
        "javax.sql.DataSource",
    ]
)
@ConditionalOnProperty(prefix = "bluetape4k.aws.exposed", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AwsExposedProperties::class)
class AwsExposedAutoConfiguration {

    /**
     * 이미 bind된 properties를 그대로 유지하는 기본 resolver를 반환합니다.
     */
    @Bean
    @ConditionalOnMissingBean(AwsDatabaseSettingsResolver::class)
    fun awsDatabaseSettingsResolver(
        environment: Environment,
    ): AwsDatabaseSettingsResolver =
        SpringEnvironmentAwsDatabaseSettingsResolver(environment)

    /**
     * registry가 사용할 기본 database factory를 생성합니다.
     */
    @Bean
    @ConditionalOnMissingBean
    fun awsExposedDatabaseFactory(
        resolver: AwsDatabaseSettingsResolver,
    ): AwsExposedDatabaseFactory =
        AwsExposedDatabaseFactory(resolver = resolver)

    /**
     * 기본 및 named Exposed database registry를 생성합니다.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @Conditional(AwsExposedDatabaseConfiguredCondition::class)
    fun awsExposedDatabaseRegistry(
        properties: AwsExposedProperties,
        factory: AwsExposedDatabaseFactory,
    ): AwsExposedDatabaseRegistry =
        // Spring @Bean factory method는 동기식이고 registry 생성은 suspend 함수입니다.
        // context startup 중 JDBC pool 초기화가 blocking 될 수 있어 IO dispatcher에서 실행합니다.
        runBlocking(Dispatchers.IO) {
            factory.createRegistry(properties.toDatabaseProperties())
        }
}

/**
 * 기본 AWS 기반 Exposed database handle의 alias bean을 노출합니다.
 *
 * 이 단계는 [AwsExposedAutoConfiguration]과 분리되어 `@ConditionalOnBean`이 앞선 auto-configuration 단계에서
 * 생성된 registry bean을 관측할 수 있게 합니다.
 */
@AutoConfiguration(after = [AwsExposedAutoConfiguration::class])
@ConditionalOnAwsEnabled
@ConditionalOnClass(
    name = [
        "io.bluetape4k.aws.exposed.AwsExposedDatabaseRegistry",
        "org.jetbrains.exposed.v1.jdbc.Database",
        "javax.sql.DataSource",
    ]
)
@ConditionalOnProperty(prefix = "bluetape4k.aws.exposed", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class AwsExposedDefaultDatabaseAutoConfiguration {

    /**
     * lifecycle을 넘겨받지 않고 기본 registry handle을 노출합니다.
     */
    @Bean(destroyMethod = "")
    @ConditionalOnBean(AwsExposedDatabaseRegistry::class)
    @ConditionalOnMissingBean
    fun awsExposedDatabaseHandle(
        registry: AwsExposedDatabaseRegistry,
    ): AwsExposedDatabaseHandle =
        registry.defaultHandle

    /**
     * lifecycle을 넘겨받지 않고 기본 JDBC data source를 노출합니다.
     */
    @Bean(destroyMethod = "")
    @ConditionalOnBean(AwsExposedDatabaseRegistry::class)
    @ConditionalOnMissingBean(DataSource::class)
    fun awsExposedDataSource(
        registry: AwsExposedDatabaseRegistry,
    ): DataSource =
        registry.defaultHandle.dataSource

    /**
     * lifecycle을 넘겨받지 않고 기본 Exposed database를 노출합니다.
     */
    @Bean(destroyMethod = "")
    @ConditionalOnBean(AwsExposedDatabaseRegistry::class)
    @ConditionalOnMissingBean(Database::class)
    fun awsExposedDatabase(
        registry: AwsExposedDatabaseRegistry,
    ): Database =
        registry.defaultHandle.database
}

internal class AwsExposedDatabaseConfiguredCondition: Condition {
    override fun matches(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ): Boolean {
        val environment = context.environment
        return environment.containsProperty("$DEFAULT_DATABASE_PREFIX.url") ||
            environment.hasConfiguredSource("$DEFAULT_DATABASE_PREFIX.secret-source") ||
            environment.hasConfiguredSource("$DEFAULT_DATABASE_PREFIX.parameter-source")
    }

    private fun Environment.hasConfiguredSource(prefix: String): Boolean =
        containsProperty("$prefix.source-id") && containsProperty("$prefix.prefix")

    private companion object {
        const val DEFAULT_DATABASE_PREFIX = "bluetape4k.aws.exposed.default-database"
    }
}
