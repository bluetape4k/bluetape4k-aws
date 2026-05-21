package io.bluetape4k.aws.ktor.exposed

import io.bluetape4k.aws.exposed.AwsDatabaseAuthenticationMode
import io.bluetape4k.aws.exposed.AwsDatabaseConfigSource
import io.bluetape4k.aws.exposed.AwsDatabaseConfigSourceType
import io.bluetape4k.aws.exposed.AwsDatabaseConnectionProperties
import io.bluetape4k.aws.exposed.AwsDatabasePoolProperties
import io.bluetape4k.aws.exposed.AwsDatabaseProperties
import io.bluetape4k.aws.exposed.AwsDatabaseSettingsResolver
import io.bluetape4k.aws.exposed.AwsExposedDatabaseFactory
import io.bluetape4k.aws.exposed.AwsExposedDatabaseRegistry
import io.bluetape4k.aws.exposed.AwsRdsIamAuthenticationProperties
import io.bluetape4k.aws.exposed.AwsSecretString
import io.bluetape4k.aws.exposed.NoopAwsDatabaseSettingsResolver
import io.bluetape4k.support.requireNotBlank
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for [AwsExposedPlugin].
 *
 * ## Contract
 *
 * Use either [databaseProperties] or the DSL methods [defaultDatabase] and
 * [database]. Mixing both configuration styles is rejected to avoid silent
 * precedence rules. Password strings are converted to [AwsSecretString] so
 * generated diagnostics stay redacted.
 *
 * ```kotlin
 * install(AwsExposedPlugin) {
 *     defaultDatabase {
 *         url = "jdbc:h2:mem:orders;MODE=PostgreSQL"
 *         driverClassName = "org.h2.Driver"
 *         username = "sa"
 *         password = "secret"
 *     }
 * }
 * ```
 */
class AwsExposedPluginConfig {

    /** Context used by route-level Exposed JDBC suspend transactions. */
    var transactionContext: CoroutineContext = Dispatchers.IO

    /** Maximum time allowed for plugin startup registry creation. */
    var startTimeout: Duration = 30.seconds

    /** Maximum time allowed for plugin shutdown registry close. */
    var stopTimeout: Duration = 10.seconds

    /** Resolver used before the shared Exposed factory creates data sources. */
    var settingsResolver: AwsDatabaseSettingsResolver = NoopAwsDatabaseSettingsResolver

    private var explicitDatabaseProperties: AwsDatabaseProperties? = null
    private var defaultDatabaseConfig: AwsExposedConnectionConfig? = null
    private val namedDatabaseConfigs = linkedMapOf<String, AwsExposedConnectionConfig>()
    private var registryFactory:
        (suspend (AwsDatabaseProperties, AwsDatabaseSettingsResolver) -> AwsExposedDatabaseRegistry)? = null

    /**
     * Uses pre-built shared database properties instead of the Ktor DSL.
     */
    fun databaseProperties(properties: AwsDatabaseProperties) {
        require(defaultDatabaseConfig == null && namedDatabaseConfigs.isEmpty()) {
            "databaseProperties cannot be combined with defaultDatabase/database DSL configuration."
        }
        explicitDatabaseProperties = properties
    }

    /**
     * Configures the default Exposed database.
     */
    fun defaultDatabase(configure: AwsExposedConnectionConfig.() -> Unit) {
        require(explicitDatabaseProperties == null) {
            "defaultDatabase DSL cannot be combined with databaseProperties."
        }
        defaultDatabaseConfig = AwsExposedConnectionConfig().apply(configure)
    }

    /**
     * Configures a named Exposed database.
     */
    fun database(
        name: String,
        configure: AwsExposedConnectionConfig.() -> Unit,
    ) {
        require(explicitDatabaseProperties == null) {
            "database DSL cannot be combined with databaseProperties."
        }
        name.requireNotBlank("name")
        require(name !in namedDatabaseConfigs) { "Database '$name' is already configured." }
        namedDatabaseConfigs[name] = AwsExposedConnectionConfig().apply(configure)
    }

    /**
     * Overrides registry creation. This is primarily useful for tests and for
     * applications that need a custom [AwsExposedDatabaseFactory].
     */
    fun registryFactory(
        factory: suspend (AwsDatabaseProperties, AwsDatabaseSettingsResolver) -> AwsExposedDatabaseRegistry,
    ) {
        registryFactory = factory
    }

    internal fun toRuntimeConfig(): AwsExposedKtorRuntimeConfig {
        require(startTimeout.isPositive()) { "startTimeout must be positive." }
        require(stopTimeout.isPositive()) { "stopTimeout must be positive." }

        val properties = explicitDatabaseProperties ?: AwsDatabaseProperties(
            defaultDatabase = defaultDatabaseConfig?.toConnectionProperties()
                ?: AwsDatabaseConnectionProperties(),
            namedDatabases = namedDatabaseConfigs.mapValues { (_, config) -> config.toConnectionProperties() },
        )

        return AwsExposedKtorRuntimeConfig(
            databaseProperties = properties,
            registryFactory = registryFactory ?: { databaseProperties, resolver ->
                AwsExposedDatabaseFactory(resolver = resolver).createRegistry(databaseProperties)
            },
            settingsResolver = settingsResolver,
            transactionContext = transactionContext,
            startTimeout = startTimeout,
            stopTimeout = stopTimeout,
        )
    }
}

/**
 * Mutable Ktor DSL for one [AwsDatabaseConnectionProperties] value.
 */
class AwsExposedConnectionConfig {

    /** JDBC URL. It may be supplied by [secretSource] or [parameterSource]. */
    var url: String = ""

    /** Optional JDBC driver class name. */
    var driverClassName: String? = null

    /** Optional JDBC username. */
    var username: String? = null

    /** Optional static JDBC password. Rendered as redacted after conversion. */
    var password: String? = null

    /** Additional Hikari data source properties. */
    var dataSourceProperties: Map<String, String> = emptyMap()

    /** Caller metadata preserved with the connection properties. */
    var metadata: Map<String, String> = emptyMap()

    /** Authentication mode used by the shared Exposed foundation. */
    var authenticationMode: AwsDatabaseAuthenticationMode = AwsDatabaseAuthenticationMode.STATIC_PASSWORD

    /** Optional RDS IAM authentication settings. */
    var rdsIam: AwsRdsIamAuthenticationProperties? = null

    private val poolConfig = AwsExposedPoolConfig()
    private var secretSource: AwsDatabaseConfigSource? = null
    private var parameterSource: AwsDatabaseConfigSource? = null

    /**
     * Configures Hikari pool settings.
     */
    fun pool(configure: AwsExposedPoolConfig.() -> Unit) {
        poolConfig.configure()
    }

    /**
     * Uses an AWS Secrets Manager source descriptor for this database.
     */
    fun secretSource(
        sourceId: String,
        configure: AwsExposedConfigSourceConfig.() -> Unit = {},
    ) {
        secretSource = AwsExposedConfigSourceConfig()
            .apply(configure)
            .toConfigSource(AwsDatabaseConfigSourceType.SECRETS_MANAGER, sourceId)
    }

    /**
     * Uses an AWS Systems Manager Parameter Store source descriptor for this database.
     */
    fun parameterSource(
        sourceId: String,
        configure: AwsExposedConfigSourceConfig.() -> Unit = {},
    ) {
        parameterSource = AwsExposedConfigSourceConfig()
            .apply(configure)
            .toConfigSource(AwsDatabaseConfigSourceType.PARAMETER_STORE, sourceId)
    }

    internal fun toConnectionProperties(): AwsDatabaseConnectionProperties {
        driverClassName?.requireNotBlank("driverClassName")
        username?.requireNotBlank("username")
        val passwordValue = password?.also { it.requireNotBlank("password") }

        return AwsDatabaseConnectionProperties(
            url = url,
            driverClassName = driverClassName,
            username = username,
            password = passwordValue?.let(AwsSecretString::of),
            pool = poolConfig.toPoolProperties(),
            dataSourceProperties = dataSourceProperties,
            metadata = metadata,
            secretSource = secretSource,
            parameterSource = parameterSource,
            authenticationMode = authenticationMode,
            rdsIam = rdsIam,
        )
    }
}

/**
 * Mutable Ktor DSL for [AwsDatabasePoolProperties].
 */
class AwsExposedPoolConfig {

    var poolName: String? = null
    var maximumPoolSize: Int = 10
    var minimumIdle: Int = 1
    var connectionTimeoutMillis: Long = 30_000L
    var idleTimeoutMillis: Long = 600_000L
    var maxLifetimeMillis: Long = 1_800_000L

    internal fun toPoolProperties(): AwsDatabasePoolProperties =
        AwsDatabasePoolProperties(
            poolName = poolName,
            maximumPoolSize = maximumPoolSize,
            minimumIdle = minimumIdle,
            connectionTimeoutMillis = connectionTimeoutMillis,
            idleTimeoutMillis = idleTimeoutMillis,
            maxLifetimeMillis = maxLifetimeMillis,
        )
}

/**
 * Mutable Ktor DSL for [AwsDatabaseConfigSource].
 */
class AwsExposedConfigSourceConfig {

    /** Optional key prefix used by the resolver when mapping remote values. */
    var prefix: String? = null

    /** Whether missing remote source values should be ignored by the resolver. */
    var optional: Boolean = false

    internal fun toConfigSource(
        type: AwsDatabaseConfigSourceType,
        sourceId: String,
    ): AwsDatabaseConfigSource {
        sourceId.requireNotBlank("sourceId")
        prefix?.requireNotBlank("prefix")

        return AwsDatabaseConfigSource(
            type = type,
            sourceId = sourceId,
            prefix = prefix,
            optional = optional,
        )
    }
}
