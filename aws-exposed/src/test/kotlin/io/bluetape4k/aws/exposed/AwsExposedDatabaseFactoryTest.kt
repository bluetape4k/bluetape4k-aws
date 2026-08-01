package io.bluetape4k.aws.exposed

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLException
import java.util.logging.Logger
import javax.sql.DataSource

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AwsExposedDatabaseFactoryTest {

    companion object: KLogging()

    @Test
    fun `secret string redacts generated connection output`() {
        val properties = AwsDatabaseConnectionProperties(
            url = "jdbc:h2:mem:redacted",
            password = awsSecretStringOf("tiger"),
        )

        properties.password?.reveal() shouldBeEqualTo "tiger"
        properties.password.toString() shouldBeEqualTo AwsSecretString.REDACTED
        properties.toString() shouldNotContain "tiger"
    }

    @Test
    fun `secret string serialization round-trip preserves redaction`() {
        val original = AwsSecretString.of("super-secret")
        val bytes = serialize(original)

        val deserialized = ObjectInputStream(ByteArrayInputStream(bytes)).use { input ->
            input.readObject()
        } as AwsSecretString

        deserialized.toString() shouldBeEqualTo AwsSecretString.REDACTED
        deserialized.reveal() shouldBeEqualTo "super-secret"
        deserialized shouldBeEqualTo original
    }

    @Test
    fun `secret string serialization rejects blank deserialized value`() {
        val bytes = serialize(AwsSecretString.of("z"))
        val markerIndexes = bytes.indices.filter { bytes[it] == 'z'.code.toByte() }
        markerIndexes.size shouldBeEqualTo 1
        // Preserve the Java serialization envelope while mutating the one-byte payload.
        bytes[markerIndexes.single()] = ' '.code.toByte()

        assertFailsWith<IllegalArgumentException> {
            ObjectInputStream(ByteArrayInputStream(bytes)).use { input ->
                input.readObject()
            }
        }
    }

    @Test
    fun `secret string factories reject blank value`() {
        assertFailsWith<IllegalArgumentException> {
            AwsSecretString.of(" ")
        }
        assertFailsWith<IllegalArgumentException> {
            awsSecretStringOf("\t")
        }
    }

    @Test
    fun `pool properties reject invalid limits`() {
        assertFailsWith<IllegalArgumentException> {
            AwsDatabasePoolProperties(maximumPoolSize = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            AwsDatabasePoolProperties(minimumIdle = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            AwsDatabasePoolProperties(connectionTimeoutMillis = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            AwsDatabasePoolProperties(idleTimeoutMillis = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            AwsDatabasePoolProperties(maxLifetimeMillis = 0)
        }
    }

    @Test
    fun `pool properties copy revalidates limits`() {
        val pool = AwsDatabasePoolProperties(maximumPoolSize = 4, minimumIdle = 1)

        assertFailsWith<IllegalArgumentException> {
            pool.copy(maximumPoolSize = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            pool.copy(minimumIdle = 5)
        }
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
    fun `handle close unregisters Exposed database and closes data source`() = runTest {
        val dataSource = CloseTrackingDataSource()
        val factory = AwsExposedDatabaseFactory(
            dataSourceFactory = AwsJdbcDataSourceFactory { _, _ -> dataSource }
        )
        val handle = factory.create(properties = h2Properties("unregister_on_close"))

        handle.close()

        (TransactionManager.primaryDatabase !== handle.database).shouldBeTrue()
        dataSource.closed.shouldBeTrue()
    }

    @Test
    fun `factory closes provisional data source when Exposed connection fails`() = runTest {
        val dataSource = CloseTrackingDataSource()
        val failure = IllegalStateException("connect failed")
        val factory = AwsExposedDatabaseFactory(
            resolver = NoopAwsDatabaseSettingsResolver,
            dataSourceFactory = AwsJdbcDataSourceFactory { _, _ -> dataSource },
            databaseConnector = { throw failure },
            testing = Unit,
        )

        val actual = assertFailsWith<IllegalStateException> {
            factory.create(properties = h2Properties("connect_failure"))
        }

        actual shouldBeSameInstanceAs failure
        dataSource.closed.shouldBeTrue()
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
    fun `named database keys reject reserved default name`() {
        val error = assertFailsWith<IllegalArgumentException> {
            AwsDatabaseProperties(
                namedDatabases = mapOf(
                    AwsExposedDatabaseFactory.DEFAULT_DATABASE_NAME to h2Properties("reserved_default"),
                )
            )
        }

        error.message.orEmpty() shouldContain AwsExposedDatabaseFactory.DEFAULT_DATABASE_NAME
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

        defaultDataSource.closed.shouldBeTrue()
    }

    @Test
    fun `factory creates PostgreSQL Testcontainers Exposed database`() = runTest {
        val postgres = PostgreSQLServer.Launcher.postgres
        val databaseFactory = AwsExposedDatabaseFactory()
        val handle = databaseFactory.create(
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

    private fun serialize(value: AwsSecretString): ByteArray {
        return ByteArrayOutputStream().use {
            ObjectOutputStream(it).use { output ->
                output.writeObject(value)
            }
            it.toByteArray()
        }
    }

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
