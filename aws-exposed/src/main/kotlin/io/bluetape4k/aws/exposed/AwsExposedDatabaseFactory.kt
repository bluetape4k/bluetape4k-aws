package io.bluetape4k.aws.exposed

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotBlank
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * AWS 기반 database 설정으로 Exposed [Database] handle을 생성합니다.
 *
 * factory는 [AwsDatabaseSettingsResolver]로 설정을 해석하고 Hikari [DataSource]를 만든 뒤
 * `Database.connect(dataSource)`로 Exposed를 연결합니다. transaction 실행은 `bluetape4k-exposed`
 * repository 관례에 맞게 호출자 책임으로 남깁니다.
 */
class AwsExposedDatabaseFactory(
    private val resolver: AwsDatabaseSettingsResolver = NoopAwsDatabaseSettingsResolver,
    private val dataSourceFactory: AwsJdbcDataSourceFactory = HikariAwsJdbcDataSourceFactory,
) {
    companion object: KLoggingChannel() {
        /**
         * 기본 database handle에 사용하는 이름입니다.
         */
        const val DEFAULT_DATABASE_NAME: String = "default"
    }

    /**
     * 이름이 지정된 Exposed database handle 하나를 해석하고 생성합니다.
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
     * 기본 database와 named database를 포함하는 registry를 생성합니다.
     *
     * 뒤쪽 database 생성 중 실패하면 이미 생성된 handle을 닫은 뒤 원래 실패를 다시 던집니다.
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
