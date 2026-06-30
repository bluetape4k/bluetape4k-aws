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

/**
 * Spring Boot auto-configuration for AWS-backed Exposed database registries.
 *
 * ## Contract
 *
 * Creates the common `bluetape4k-aws-exposed` registry only when the default
 * database URL or a source descriptor is configured. Secrets Manager and
 * Parameter Store integration is provided by the existing environment
 * post-processors that publish properties before registry creation.
 */
@AutoConfiguration(after = [AwsAutoConfiguration::class])
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
     * Returns the default resolver that keeps already-bound properties
     * unchanged.
     */
    @Bean
    @ConditionalOnMissingBean(AwsDatabaseSettingsResolver::class)
    fun awsDatabaseSettingsResolver(
        environment: Environment,
    ): AwsDatabaseSettingsResolver =
        SpringEnvironmentAwsDatabaseSettingsResolver(environment)

    /**
     * Creates the default database factory used by the registry.
     */
    @Bean
    @ConditionalOnMissingBean
    fun awsExposedDatabaseFactory(
        resolver: AwsDatabaseSettingsResolver,
    ): AwsExposedDatabaseFactory =
        AwsExposedDatabaseFactory(resolver = resolver)

    /**
     * Creates the default and named Exposed database registry.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @Conditional(AwsExposedDatabaseConfiguredCondition::class)
    fun awsExposedDatabaseRegistry(
        properties: AwsExposedProperties,
        factory: AwsExposedDatabaseFactory,
    ): AwsExposedDatabaseRegistry =
        // Spring @Bean factory methods are synchronous; registry creation is suspend
        // and may initialize blocking JDBC pools during one-time context startup.
        runBlocking(Dispatchers.IO) {
            factory.createRegistry(properties.toDatabaseProperties())
        }
}

/**
 * Exposes aliases for the default AWS-backed Exposed database handle.
 *
 * This phase is split from [AwsExposedAutoConfiguration] so
 * `@ConditionalOnBean` observes a registry bean created by an earlier
 * auto-configuration phase.
 */
@AutoConfiguration(after = [AwsExposedAutoConfiguration::class])
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
     * Exposes the default registry handle without taking over lifecycle.
     */
    @Bean(destroyMethod = "")
    @ConditionalOnBean(AwsExposedDatabaseRegistry::class)
    @ConditionalOnMissingBean
    fun awsExposedDatabaseHandle(
        registry: AwsExposedDatabaseRegistry,
    ): AwsExposedDatabaseHandle =
        registry.defaultHandle

    /**
     * Exposes the default JDBC data source without taking over lifecycle.
     */
    @Bean(destroyMethod = "")
    @ConditionalOnBean(AwsExposedDatabaseRegistry::class)
    @ConditionalOnMissingBean(DataSource::class)
    fun awsExposedDataSource(
        registry: AwsExposedDatabaseRegistry,
    ): DataSource =
        registry.defaultHandle.dataSource

    /**
     * Exposes the default Exposed database without taking over lifecycle.
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
