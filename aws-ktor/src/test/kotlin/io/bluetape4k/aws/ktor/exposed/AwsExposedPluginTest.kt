package io.bluetape4k.aws.ktor.exposed

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.aws.exposed.AwsDatabaseConfigSourceType
import io.bluetape4k.aws.exposed.AwsDatabaseConnectionProperties
import io.bluetape4k.aws.exposed.AwsDatabasePoolProperties
import io.bluetape4k.aws.exposed.AwsDatabaseProperties
import io.bluetape4k.aws.exposed.AwsDatabaseSettingsResolver
import io.bluetape4k.aws.exposed.AwsExposedDatabaseFactory
import io.bluetape4k.aws.exposed.AwsExposedDatabaseHandle
import io.bluetape4k.aws.exposed.AwsExposedDatabaseRegistry
import io.bluetape4k.aws.exposed.AwsSecretString
import io.bluetape4k.aws.exposed.NoopAwsDatabaseSettingsResolver
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AwsExposedPluginTest {

    @Test
    fun `application helper fails before plugin install`() = testApplication {
        application {
            val error = assertFailsWith<IllegalStateException> {
                awsExposed()
            }

            error.message shouldContain "not installed"
        }
    }

    @Test
    fun `runtime access fails before start`() = runSuspendIO {
        val runtime = runtime(registry = closeTrackingRegistry("before_start"))

        val error = assertFailsWith<IllegalStateException> {
            runtime.registry
        }

        error.message shouldContain "not started"
    }

    @Test
    fun `runtime starts once and closes registry once`() = runSuspendIO {
        val dataSource = CloseTrackingDataSource()
        val registry = registry("close_once", dataSource)
        val runtime = runtime(registry)

        runtime.start()
        runtime.start()

        runtime.registry shouldBeSameInstanceAs registry

        runtime.stop()
        runtime.stop()

        dataSource.closeCount.get() shouldBeEqualTo 1
    }

    @Test
    fun `plugin route runs suspend transaction`() = testApplication {
        application {
            install(AwsExposedPlugin) {
                defaultDatabase {
                    url = h2Url("route_${UUID.randomUUID()}")
                    driverClassName = H2_DRIVER
                    username = "sa"
                    pool {
                        maximumPoolSize = 2
                        minimumIdle = 0
                    }
                }
            }
            routing {
                get("/count") {
                    val count = call.awsExposedTransaction {
                        SchemaUtils.drop(RouteRows)
                        SchemaUtils.create(RouteRows)
                        RouteRows.insert {
                            it[name] = "ktor"
                        }
                        RouteRows.selectAll().count()
                    }
                    call.respondText(count.toString())
                }
            }
        }

        startApplication()

        client.get("/count").bodyAsText() shouldBeEqualTo "1"
    }

    @Test
    fun `plugin resolves named database handles`() = testApplication {
        application {
            install(AwsExposedPlugin) {
                defaultDatabase {
                    url = h2Url("named_default_${UUID.randomUUID()}")
                    driverClassName = H2_DRIVER
                    username = "sa"
                }
                database("analytics") {
                    url = h2Url("named_analytics_${UUID.randomUUID()}")
                    driverClassName = H2_DRIVER
                    username = "sa"
                }
            }
            routing {
                get("/named") {
                    call.respondText(call.awsExposedHandle("analytics").properties.url)
                }
            }
        }

        startApplication()

        client.get("/named").bodyAsText() shouldContain "named_analytics"
    }

    @Test
    fun `resolver preserves source descriptor and redacts secret values`() = testApplication {
        application {
            install(AwsExposedPlugin) {
                settingsResolver = AwsDatabaseSettingsResolver { databaseName, properties ->
                    databaseName shouldBeEqualTo AwsExposedDatabaseFactory.DEFAULT_DATABASE_NAME
                    properties.secretSource?.type shouldBeEqualTo AwsDatabaseConfigSourceType.SECRETS_MANAGER
                    properties.secretSource?.sourceId shouldBeEqualTo "/prod/app/database"
                    properties.secretSource?.prefix shouldBeEqualTo "db"

                    properties.copy(
                        url = h2Url("resolver_${UUID.randomUUID()}"),
                        driverClassName = H2_DRIVER,
                        username = "sa",
                        password = AwsSecretString.of(SENTINEL_SECRET),
                    )
                }
                defaultDatabase {
                    secretSource("/prod/app/database") {
                        prefix = "db"
                    }
                }
            }
        }

        startApplication()

        val handle = application.awsExposedHandle()
        handle.properties.password.toString() shouldBeEqualTo AwsSecretString.REDACTED
        handle.properties.toString() shouldNotContain SENTINEL_SECRET
        application.awsExposed().toString() shouldNotContain SENTINEL_SECRET
    }

    @Test
    fun `start timeout fails clearly`() = runSuspendIO {
        val runtime = AwsExposedKtorRuntime(
            AwsExposedKtorRuntimeConfig(
                databaseProperties = databaseProperties("timeout"),
                registryFactory = { _, _ ->
                    delay(200.milliseconds)
                    closeTrackingRegistry("timeout")
                },
                settingsResolver = NoopAwsDatabaseSettingsResolver,
                transactionContext = Dispatchers.IO,
                startTimeout = 50.milliseconds,
                stopTimeout = 1.seconds,
            )
        )

        val error = assertFailsWith<IllegalStateException> {
            runtime.start()
        }

        error.message shouldContain "Timed out while starting"
    }

    @Test
    fun `stop timeout does not leave runtime started`() = runSuspendIO {
        val runtime = runtime(
            registry = registry("slow_close", InterruptibleCloseDataSource()),
            stopTimeout = 50.milliseconds,
        )

        runtime.start()
        runtime.stop()

        val error = assertFailsWith<IllegalStateException> {
            runtime.registry
        }
        error.message shouldContain "not started"
    }

    @Test
    fun `transaction propagates exception and rolls back`() = testApplication {
        application {
            install(AwsExposedPlugin) {
                defaultDatabase {
                    url = h2Url("rollback_${UUID.randomUUID()}")
                    driverClassName = H2_DRIVER
                    username = "sa"
                }
            }
            routing {
                get("/rollback") {
                    awsExposedTransaction {
                        SchemaUtils.drop(RollbackRows)
                        SchemaUtils.create(RollbackRows)
                    }

                    val error = assertFailsWith<IllegalStateException> {
                        awsExposedTransaction {
                            RollbackRows.insert {
                                it[name] = "rolled-back"
                            }
                            throw IllegalStateException("rollback sentinel")
                        }
                    }

                    val count = awsExposedTransaction {
                        RollbackRows.selectAll().count()
                    }

                    call.respondText("${error.message}:$count")
                }
            }
        }

        startApplication()

        client.get("/rollback").bodyAsText() shouldBeEqualTo "rollback sentinel:0"
    }

    @Test
    fun `configuration styles are mutually exclusive`() {
        val config = AwsExposedPluginConfig()
        config.databaseProperties(AwsDatabaseProperties(defaultDatabase = properties("explicit")))

        val error = assertFailsWith<IllegalArgumentException> {
            config.defaultDatabase {
                url = h2Url("mixed")
            }
        }

        error.message shouldContain "cannot be combined"
    }

    private fun runtime(
        registry: AwsExposedDatabaseRegistry,
        stopTimeout: kotlin.time.Duration = 1.seconds,
    ): AwsExposedKtorRuntime =
        AwsExposedKtorRuntime(
            AwsExposedKtorRuntimeConfig(
                databaseProperties = databaseProperties("runtime"),
                registryFactory = { _, _ -> registry },
                settingsResolver = NoopAwsDatabaseSettingsResolver,
                transactionContext = Dispatchers.IO,
                startTimeout = 1.seconds,
                stopTimeout = stopTimeout,
            )
        )

    private fun closeTrackingRegistry(name: String): AwsExposedDatabaseRegistry =
        registry(name, CloseTrackingDataSource())

    private fun registry(
        name: String,
        dataSource: DataSource,
    ): AwsExposedDatabaseRegistry {
        val properties = properties(name)
        val handle = AwsExposedDatabaseHandle(
            name = AwsExposedDatabaseFactory.DEFAULT_DATABASE_NAME,
            properties = properties,
            dataSource = dataSource,
            database = Database.connect(dataSource),
        )

        return AwsExposedDatabaseRegistry(
            defaultHandle = handle,
            namedHandles = emptyMap(),
        )
    }

    private fun properties(name: String): AwsDatabaseConnectionProperties =
        AwsDatabaseConnectionProperties(
            url = h2Url(name),
            driverClassName = H2_DRIVER,
            username = "sa",
            pool = AwsDatabasePoolProperties(maximumPoolSize = 2, minimumIdle = 0),
        )

    private fun databaseProperties(name: String): AwsDatabaseProperties =
        AwsDatabaseProperties(defaultDatabase = properties(name))

    private fun h2Url(databaseName: String): String =
        "jdbc:h2:mem:$databaseName;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"

    private object RouteRows: Table("aws_exposed_ktor_route_rows") {
        val id = integer("id").autoIncrement()
        val name = varchar("name", 64)

        override val primaryKey = PrimaryKey(id)
    }

    private object RollbackRows: Table("aws_exposed_ktor_rollback_rows") {
        val id = integer("id").autoIncrement()
        val name = varchar("name", 64)

        override val primaryKey = PrimaryKey(id)
    }

    private open class CloseTrackingDataSource: DataSource, AutoCloseable {
        val closeCount = AtomicInteger()

        override fun close() {
            closeCount.incrementAndGet()
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

    private class InterruptibleCloseDataSource: CloseTrackingDataSource() {
        override fun close() {
            try {
                Thread.sleep(5_000)
            } finally {
                super.close()
            }
        }
    }

    private companion object {
        private const val H2_DRIVER = "org.h2.Driver"
        private const val SENTINEL_SECRET = "sentinel-secret"
    }
}
