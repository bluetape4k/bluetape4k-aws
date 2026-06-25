package io.bluetape4k.aws.exposed

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotBlank
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Creates Exposed [Database] handles from AWS-backed database settings.
 *
 * The factory resolves settings through [AwsDatabaseSettingsResolver], creates a
 * Hikari [DataSource], and connects Exposed through `Database.connect(dataSource)`.
 * Transaction execution remains the caller's responsibility, matching
 * `bluetape4k-exposed` repository conventions.
 */
class AwsExposedDatabaseFactory(
    private val resolver: AwsDatabaseSettingsResolver = NoopAwsDatabaseSettingsResolver,
    private val dataSourceFactory: AwsJdbcDataSourceFactory = HikariAwsJdbcDataSourceFactory,
) {
    companion object: KLoggingChannel() {
        /**
         * Name used for the default database handle.
         */
        const val DEFAULT_DATABASE_NAME: String = "default"
    }

    /**
     * Resolves and creates one named Exposed database handle.
     */
    suspend fun create(
        databaseName: String = DEFAULT_DATABASE_NAME,
        properties: AwsDatabaseConnectionProperties,
    ): AwsExposedDatabaseHandle {
        databaseName.requireNotBlank("databaseName")

        val resolved = resolver.resolve(databaseName, properties)
        resolved.validateFor(databaseName)

        val dataSource = dataSourceFactory.create(databaseName, resolved)
        val database = Database.connect(dataSource)
        log.debug { "Created Exposed database handle '$databaseName'." }
        return AwsExposedDatabaseHandle(databaseName, resolved, dataSource, database)
    }

    /**
     * Creates a registry containing the default database plus named databases.
     *
     * If a later database fails during creation, previously created handles are
     * closed before the original failure is rethrown.
     */
    suspend fun createRegistry(properties: AwsDatabaseProperties): AwsExposedDatabaseRegistry {
        val created = mutableListOf<AwsExposedDatabaseHandle>()

        try {
            val defaultHandle = create(DEFAULT_DATABASE_NAME, properties.defaultDatabase)
            created += defaultHandle

            val named = properties.namedDatabases.mapValues { (name, connectionProperties) ->
                create(name, connectionProperties).also { created += it }
            }
            return AwsExposedDatabaseRegistry(defaultHandle, named)
        } catch (e: Throwable) {
            closeCreatedHandles(created, e)
            throw e
        }
    }

    private fun AwsDatabaseConnectionProperties.validateFor(databaseName: String) {
        url.requireNotBlank("$databaseName.url")
        driverClassName?.let {
            runCatching { Class.forName(it) }
                .getOrElse { e ->
                    throw IllegalArgumentException("JDBC driver class not found for '$databaseName': $it", e)
                }
        }
    }

    private fun closeCreatedHandles(
        handles: List<AwsExposedDatabaseHandle>,
        owner: Throwable,
    ) {
        handles.asReversed().forEach { handle ->
            runCatching { handle.close() }
                .onFailure { owner.addSuppressed(it) }
        }
    }
}
