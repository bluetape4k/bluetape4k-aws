package io.bluetape4k.aws.exposed

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.jdbc.datasource.RefreshingJdbcPasswordDataSource
import io.bluetape4k.jdbc.datasource.RefreshingJdbcPasswordDataSourceConfig
import io.bluetape4k.jdbc.hikari.hikariDataSourceOf
import io.bluetape4k.logging.KLogging
import javax.sql.DataSource

/**
 * Exposed database handle에 사용할 JDBC [DataSource]를 생성합니다.
 */
fun interface AwsJdbcDataSourceFactory {

    /**
     * 이미 해석된 [properties]로 [databaseName]용 data source를 생성합니다.
     */
    fun create(
        databaseName: String,
        properties: AwsDatabaseConnectionProperties,
    ): DataSource
}

/**
 * HikariCP 기반 기본 [AwsJdbcDataSourceFactory]입니다.
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
                    val rdsIam = requireNotNull(properties.rdsIam)
                    val passwordProvider = AwsDatabasePasswordProviders.rdsIam(
                        properties = properties,
                        tokenGenerator = AwsSdkRdsIamAuthTokenGenerator(),
                    )

                    dataSource = RefreshingJdbcPasswordDataSource(
                        config = RefreshingJdbcPasswordDataSourceConfig(
                            url = properties.url,
                            driverClassName = properties.driverClassName,
                            username = rdsIam.effectiveUsername(properties.username),
                            dataSourceProperties = properties.dataSourceProperties,
                            nullPasswordMessage = "RDS IAM password provider returned null.",
                        ),
                        passwordProvider = {
                            passwordProvider.currentPassword()?.reveal()
                        },
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
