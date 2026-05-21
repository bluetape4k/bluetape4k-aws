package io.bluetape4k.aws.spring.exposed

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.aws.exposed.AwsDatabaseConnectionProperties
import io.bluetape4k.aws.exposed.AwsDatabaseProperties
import io.bluetape4k.aws.exposed.AwsDatabaseSettingsResolver
import io.bluetape4k.aws.exposed.AwsExposedDatabaseFactory
import io.bluetape4k.aws.exposed.AwsExposedDatabaseRegistry
import io.bluetape4k.aws.exposed.AwsSecretString
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.MapPropertySource
import javax.sql.DataSource

class AwsExposedAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsExposedAutoConfiguration::class.java,
                AwsExposedDefaultDatabaseAutoConfiguration::class.java,
            )
        )
        .withPropertyValues(*defaultDatabaseProperties())

    @Test
    fun `register Exposed registry and default database aliases`() {
        contextRunner.run { context ->
            context.getBeansOfType(AwsExposedProperties::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(AwsDatabaseSettingsResolver::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(AwsExposedDatabaseFactory::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(AwsExposedDatabaseRegistry::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(DataSource::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(Database::class.java).size shouldBeEqualTo 1
        }
    }

    @Test
    fun `do not create registry when default database url is absent`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AwsExposedAutoConfiguration::class.java,
                    AwsExposedDefaultDatabaseAutoConfiguration::class.java,
                )
            )
            .run { context ->
                context.getBeansOfType(AwsExposedProperties::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(AwsExposedDatabaseRegistry::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(DataSource::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(Database::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `back off when Exposed auto configuration disabled`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.exposed.enabled=false")
            .run { context ->
                context.getBeansOfType(AwsExposedProperties::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(AwsExposedDatabaseRegistry::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(DataSource::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(Database::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `back off when aws exposed classes are absent`() {
        contextRunner
            .withClassLoader(FilteredClassLoader("io.bluetape4k.aws.exposed"))
            .run { context ->
                context.getBeansOfType(AwsExposedProperties::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(AwsExposedDatabaseRegistry::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(DataSource::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(Database::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `custom resolver backs off default resolver`() {
        val customResolver = AwsDatabaseSettingsResolver { _, properties ->
            properties.copy(metadata = properties.metadata + ("resolved" to "true"))
        }

        contextRunner
            .withBean(AwsDatabaseSettingsResolver::class.java, { customResolver })
            .run { context ->
                context.getBeansOfType(AwsDatabaseSettingsResolver::class.java).size shouldBeEqualTo 1
                context.getBean(AwsDatabaseSettingsResolver::class.java) shouldBeSameInstanceAs customResolver
                context.getBean(AwsExposedDatabaseRegistry::class.java)
                    .defaultHandle.properties.metadata["resolved"] shouldBeEqualTo "true"
            }
    }

    @Test
    fun `custom factory backs off default factory`() {
        val customFactory = AwsExposedDatabaseFactory()

        contextRunner
            .withBean(AwsExposedDatabaseFactory::class.java, { customFactory })
            .run { context ->
                context.getBeansOfType(AwsExposedDatabaseFactory::class.java).size shouldBeEqualTo 1
                context.getBean(AwsExposedDatabaseFactory::class.java) shouldBeSameInstanceAs customFactory
            }
    }

    @Test
    fun `custom registry backs off default registry`() {
        val customRegistry = createRegistry("custom")

        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AwsExposedAutoConfiguration::class.java))
            .withPropertyValues(*defaultDatabaseProperties("ignored"))
            .withBean(AwsExposedDatabaseRegistry::class.java, { customRegistry })
            .run { context ->
                context.getBeansOfType(AwsExposedDatabaseRegistry::class.java).size shouldBeEqualTo 1
                context.getBean(AwsExposedDatabaseRegistry::class.java) shouldBeSameInstanceAs customRegistry
            }
    }

    @Test
    fun `custom DataSource and Database beans back off default aliases`() {
        val customRegistry = createRegistry("custom-datasource")
        try {
            val dataSource = customRegistry.defaultHandle.dataSource
            val database = Database.connect(dataSource)

            contextRunner
                .withBean(DataSource::class.java, { dataSource })
                .withBean(Database::class.java, { database })
                .run { context ->
                    context.getBeansOfType(AwsExposedDatabaseRegistry::class.java).size shouldBeEqualTo 1
                    context.getBeansOfType(DataSource::class.java).size shouldBeEqualTo 1
                    context.getBeansOfType(Database::class.java).size shouldBeEqualTo 1
                    context.getBean(DataSource::class.java) shouldBeSameInstanceAs dataSource
                    context.getBean(Database::class.java) shouldBeSameInstanceAs database
                }
        } finally {
            customRegistry.close()
        }
    }

    @Test
    fun `bind named database properties into registry`() {
        contextRunner
            .withPropertyValues(
                "bluetape4k.aws.exposed.named-databases.analytics.url=${h2Url("analytics")}",
                "bluetape4k.aws.exposed.named-databases.analytics.driver-class-name=org.h2.Driver",
                "bluetape4k.aws.exposed.named-databases.analytics.username=sa",
                "bluetape4k.aws.exposed.named-databases.analytics.pool.maximum-pool-size=2",
            )
            .run { context ->
                val registry = context.getBean(AwsExposedDatabaseRegistry::class.java)
                registry.get("analytics").properties.url shouldContain "analytics"
            }
    }

    @Test
    fun `bind password as redacted secret string`() {
        contextRunner.run { context ->
            val registry = context.getBean(AwsExposedDatabaseRegistry::class.java)
            val password = registry.defaultHandle.properties.password.shouldNotBeNull()
            password.toString() shouldBeEqualTo AwsSecretString.REDACTED
            password.reveal() shouldBeEqualTo "secret"
        }
    }

    @Test
    fun `bind secret backed property source values`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AwsExposedAutoConfiguration::class.java,
                    AwsExposedDefaultDatabaseAutoConfiguration::class.java,
                )
            )
            .withInitializer { context ->
                context.environment.propertySources.addFirst(
                    MapPropertySource(
                        "aws-secret-database",
                        mapOf(
                            "bluetape4k.aws.exposed.default-database.url" to h2Url("secret"),
                            "bluetape4k.aws.exposed.default-database.driver-class-name" to "org.h2.Driver",
                            "bluetape4k.aws.exposed.default-database.username" to "sa",
                            "bluetape4k.aws.exposed.default-database.password" to "secret-from-source",
                        )
                    )
                )
            }
            .run { context ->
                val registry = context.getBean(AwsExposedDatabaseRegistry::class.java)
                registry.defaultHandle.properties.url shouldContain "secret"
                registry.defaultHandle.properties.password.shouldNotBeNull()
                    .reveal() shouldBeEqualTo "secret-from-source"
            }
    }

    @Test
    fun `default Exposed database supports JDBC transaction usage`() {
        contextRunner.run { context ->
            val database = context.getBean(Database::class.java)
            val count = transaction(database) {
                SchemaUtils.create(ExposedAutoConfigurationRows)
                ExposedAutoConfigurationRows.insert { row -> row[name] = "spring" }
                ExposedAutoConfigurationRows.selectAll().count()
            }

            count shouldBeEqualTo 1L
        }
    }

    private fun createRegistry(name: String): AwsExposedDatabaseRegistry =
        kotlinx.coroutines.runBlocking {
            AwsExposedDatabaseFactory().createRegistry(
                AwsDatabaseProperties(
                    defaultDatabase = AwsDatabaseConnectionProperties(
                        url = h2Url(name),
                        driverClassName = "org.h2.Driver",
                        username = "sa",
                    )
                )
            )
        }

    private fun defaultDatabaseProperties(name: String = "default"): Array<String> =
        arrayOf(
            "bluetape4k.aws.exposed.default-database.url=${h2Url(name)}",
            "bluetape4k.aws.exposed.default-database.driver-class-name=org.h2.Driver",
            "bluetape4k.aws.exposed.default-database.username=sa",
            "bluetape4k.aws.exposed.default-database.password=secret",
            "bluetape4k.aws.exposed.default-database.pool.maximum-pool-size=3",
            "bluetape4k.aws.exposed.default-database.pool.minimum-idle=1",
        )

    private fun h2Url(name: String): String =
        "jdbc:h2:mem:aws_exposed_$name;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
}

private object ExposedAutoConfigurationRows: Table("exposed_auto_configuration_rows") {
    val name = varchar("name", 64)
}
