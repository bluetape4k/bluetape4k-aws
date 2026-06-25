package io.bluetape4k.aws.exposed

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.jdbc.hikari.hikariDataSourceOf
import io.bluetape4k.logging.KLogging
import java.io.PrintWriter
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import java.util.logging.Logger
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
object HikariAwsJdbcDataSourceFactory: AwsJdbcDataSourceFactory, KLogging() {

    const val DEFAULT_POOL_NAME_PREFIX: String = "bluetape4k-aws-exposed"

    override fun create(
        databaseName: String,
        properties: AwsDatabaseConnectionProperties,
    ): HikariDataSource {
        return hikariDataSourceOf {
            when (properties.authenticationMode) {
                AwsDatabaseAuthenticationMode.STATIC_PASSWORD -> {
                    jdbcUrl = properties.url
                    properties.driverClassName?.let { driverClassName = it }
                    properties.username?.let { username = it }
                    properties.password?.let { password = it.reveal() }

                    properties.dataSourceProperties.forEach { (key, value) ->
                        addDataSourceProperty(key, value)
                    }
                }
                AwsDatabaseAuthenticationMode.RDS_IAM -> {
                    dataSource = RdsIamRefreshingDataSource(
                        url = properties.url,
                        driverClassName = properties.driverClassName,
                        username = requireNotNull(properties.rdsIam).effectiveUsername(properties.username),
                        dataSourceProperties = properties.dataSourceProperties,
                        passwordProvider = AwsDatabasePasswordProviders.rdsIam(
                            properties = properties,
                            tokenGenerator = AwsSdkRdsIamAuthTokenGenerator(),
                        ),
                    )
                }
            }

            poolName = properties.pool.poolName ?: defaultPoolName(databaseName)
            maximumPoolSize = properties.pool.maximumPoolSize
            minimumIdle = properties.pool.minimumIdle
            connectionTimeout = properties.pool.connectionTimeoutMillis
            idleTimeout = properties.pool.idleTimeoutMillis
            maxLifetime = properties.pool.maxLifetimeMillis
        }
    }

    private fun defaultPoolName(databaseName: String): String =
        "$DEFAULT_POOL_NAME_PREFIX-$databaseName"
}

internal class RdsIamRefreshingDataSource(
    private val url: String,
    driverClassName: String?,
    private val username: String,
    private val dataSourceProperties: Map<String, String>,
    private val passwordProvider: AwsDatabasePasswordProvider,
): DataSource {

    companion object: KLogging()

    init {
        driverClassName?.let { Class.forName(it) }
    }

    override fun getConnection(): Connection {
        val connectionProperties = Properties().apply {
            dataSourceProperties.forEach { (key, value) -> setProperty(key, value) }
            setProperty("user", username)
            setProperty(
                "password",
                requireNotNull(passwordProvider.currentPassword()) {
                    "RDS IAM password provider returned null."
                }.reveal(),
            )
        }
        return DriverManager.getConnection(url, connectionProperties)
    }

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection =
        throw SQLException("RDS IAM data source does not accept caller-supplied credentials.")

    override fun getLogWriter(): PrintWriter? = DriverManager.getLogWriter()

    override fun setLogWriter(out: PrintWriter?) {
        DriverManager.setLogWriter(out)
    }

    override fun setLoginTimeout(seconds: Int) {
        DriverManager.setLoginTimeout(seconds)
    }

    override fun getLoginTimeout(): Int = DriverManager.getLoginTimeout()

    override fun getParentLogger(): Logger = Logger.getGlobal()

    override fun <T: Any?> unwrap(iface: Class<T>): T {
        if (iface.isInstance(this)) {
            return iface.cast(this)
        }
        throw SQLException("Not a wrapper for ${iface.name}.")
    }

    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)
}
