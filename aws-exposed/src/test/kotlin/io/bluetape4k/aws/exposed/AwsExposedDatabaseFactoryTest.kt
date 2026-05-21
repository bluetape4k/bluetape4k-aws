package io.bluetape4k.aws.exposed

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLException
import java.util.logging.Logger
import javax.sql.DataSource

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AwsExposedDatabaseFactoryTest {

    @Test
    fun `secret string redacts generated connection output`() {
        val properties = AwsDatabaseConnectionProperties(
            url = "jdbc:h2:mem:redacted",
            password = AwsSecretString.of("tiger"),
        )

        properties.password?.reveal() shouldBeEqualTo "tiger"
        properties.password.toString() shouldBeEqualTo AwsSecretString.REDACTED
        properties.toString().contains("tiger").shouldBeFalse()
    }

    @Test
    fun `factory creates H2 Exposed database`() = runTest {
        val handle = AwsExposedDatabaseFactory().create(
            properties = h2Properties("factory_creates_h2"),
        )

        handle.use {
            verifyCreateRead(it, "aws_exposed_h2_items", "h2")
        }
    }

    @Test
    fun `resolver can provide final database settings`() = runTest {
        val resolver = AwsDatabaseSettingsResolver { databaseName, properties ->
            databaseName shouldBeEqualTo AwsExposedDatabaseFactory.DEFAULT_DATABASE_NAME
            properties.copy(url = h2Url("resolver_settings"), driverClassName = "org.h2.Driver")
        }
        val handle = AwsExposedDatabaseFactory(resolver = resolver).create(
            properties = AwsDatabaseConnectionProperties(),
        )

        handle.use {
            verifyCreateRead(it, "aws_exposed_resolver_items", "resolved")
        }
    }

    @Test
    fun `registry resolves default and named handles`() = runTest {
        val registry = AwsExposedDatabaseFactory().createRegistry(
            AwsDatabaseProperties(
                defaultDatabase = h2Properties("registry_default"),
                namedDatabases = mapOf("analytics" to h2Properties("registry_analytics")),
            )
        )

        registry.use {
            it.get() shouldBeSameInstanceAs it.defaultHandle
            it.get(AwsExposedDatabaseFactory.DEFAULT_DATABASE_NAME) shouldBeSameInstanceAs it.defaultHandle
            it.get("analytics") shouldBeSameInstanceAs it.namedHandles.getValue("analytics")
        }
    }

    @Test
    fun `registry closes created handles when later database creation fails`() = runTest {
        val defaultDataSource = CloseTrackingDataSource()
        val dataSourceFactory = AwsJdbcDataSourceFactory { databaseName, _ ->
            if (databaseName == "broken") {
                throw IllegalStateException("broken data source")
            }
            defaultDataSource
        }
        val factory = AwsExposedDatabaseFactory(dataSourceFactory = dataSourceFactory)

        assertFailsWith<IllegalStateException> {
            factory.createRegistry(
                AwsDatabaseProperties(
                    defaultDatabase = h2Properties("partial_default"),
                    namedDatabases = mapOf("broken" to h2Properties("partial_broken")),
                )
            )
        }

        defaultDataSource.closed shouldBeEqualTo true
    }

    @Test
    fun `factory creates PostgreSQL Testcontainers Exposed database`() = runTest {
        val postgres = PostgreSQLServer.Launcher.postgres
        val handle = AwsExposedDatabaseFactory().create(
            properties = AwsDatabaseConnectionProperties(
                url = postgres.getJdbcUrl(),
                driverClassName = postgres.getDriverClassName(),
                username = postgres.getUsername(),
                password = postgres.getPassword()?.let(AwsSecretString::of),
                pool = AwsDatabasePoolProperties(maximumPoolSize = 2, minimumIdle = 0),
            )
        )

        handle.use {
            verifyCreateRead(it, "aws_exposed_postgres_items", "postgres")
        }
    }

    private fun h2Properties(databaseName: String): AwsDatabaseConnectionProperties =
        AwsDatabaseConnectionProperties(
            url = h2Url(databaseName),
            driverClassName = "org.h2.Driver",
            username = "sa",
            pool = AwsDatabasePoolProperties(maximumPoolSize = 2, minimumIdle = 0),
        )

    private fun h2Url(databaseName: String): String =
        "jdbc:h2:mem:$databaseName;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"

    private fun verifyCreateRead(
        handle: AwsExposedDatabaseHandle,
        tableName: String,
        value: String,
    ) {
        val table = TestItems(tableName)
        transaction(handle.database) {
            SchemaUtils.drop(table)
            SchemaUtils.create(table)
            table.insert {
                it[id] = 1
                it[name] = value
            }

            val actual = table
                .selectAll()
                .where { table.id eq 1 }
                .single()[table.name]

            actual shouldBeEqualTo value
            SchemaUtils.drop(table)
        }
    }

    private class TestItems(tableName: String): Table(tableName) {
        val id = integer("id")
        val name = varchar("name", 64)

        override val primaryKey = PrimaryKey(id)
    }

    private class CloseTrackingDataSource: DataSource, AutoCloseable {
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
        }

        override fun getConnection(): Connection =
            throw SQLException("Connection should not be requested in this test.")

        override fun getConnection(username: String?, password: String?): Connection =
            throw SQLException("Connection should not be requested in this test.")

        override fun getLogWriter(): PrintWriter? = null

        override fun setLogWriter(out: PrintWriter?) = Unit

        override fun setLoginTimeout(seconds: Int) = Unit

        override fun getLoginTimeout(): Int = 0

        override fun getParentLogger(): Logger = Logger.getGlobal()

        override fun <T: Any?> unwrap(iface: Class<T>): T =
            throw SQLException("Not a wrapper.")

        override fun isWrapperFor(iface: Class<*>?): Boolean = false
    }
}
