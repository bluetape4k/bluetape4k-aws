package io.bluetape4k.aws.exposed

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

/**
 * Creates a JDBC [DataSource] for an Exposed database handle.
 */
fun interface AwsJdbcDataSourceFactory {

    /**
     * Creates a data source for [databaseName] from already-resolved [properties].
     */
    fun create(
        databaseName: String,
        properties: AwsDatabaseConnectionProperties,
    ): DataSource
}

/**
 * Default [AwsJdbcDataSourceFactory] backed by HikariCP.
 */
object HikariAwsJdbcDataSourceFactory: AwsJdbcDataSourceFactory {

    override fun create(
        databaseName: String,
        properties: AwsDatabaseConnectionProperties,
    ): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = properties.url
            properties.driverClassName?.let { driverClassName = it }
            properties.username?.let { username = it }
            properties.password?.let { password = it.reveal() }

            poolName = properties.pool.poolName ?: defaultPoolName(databaseName)
            maximumPoolSize = properties.pool.maximumPoolSize
            minimumIdle = properties.pool.minimumIdle
            connectionTimeout = properties.pool.connectionTimeoutMillis
            idleTimeout = properties.pool.idleTimeoutMillis
            maxLifetime = properties.pool.maxLifetimeMillis

            properties.dataSourceProperties.forEach { (key, value) ->
                addDataSourceProperty(key, value)
            }
        }
        return HikariDataSource(config)
    }

    private fun defaultPoolName(databaseName: String): String =
        "bluetape4k-aws-exposed-$databaseName"
}
