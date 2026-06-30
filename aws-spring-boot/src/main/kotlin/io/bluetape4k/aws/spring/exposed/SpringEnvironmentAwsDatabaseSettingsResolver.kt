package io.bluetape4k.aws.spring.exposed

import io.bluetape4k.aws.exposed.AwsDatabaseAuthenticationMode
import io.bluetape4k.aws.exposed.AwsDatabaseConfigSource
import io.bluetape4k.aws.exposed.AwsDatabaseConnectionProperties
import io.bluetape4k.aws.exposed.AwsDatabasePoolProperties
import io.bluetape4k.aws.exposed.AwsDatabaseSettingsResolver
import io.bluetape4k.aws.exposed.AwsRdsIamAuthenticationProperties
import io.bluetape4k.aws.exposed.AwsSecretString
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.env.Environment
import java.time.Duration

/**
 * Resolves Exposed database settings from Spring Environment property sources.
 *
 * ## Contract
 *
 * `secretSource` and `parameterSource` descriptors point to properties already
 * loaded by the Spring Environment post-processors. This resolver never creates
 * AWS clients; it overlays only keys that actually exist under the descriptor
 * prefix and leaves the rest of the bound connection settings unchanged.
 */
internal class SpringEnvironmentAwsDatabaseSettingsResolver(
    private val environment: Environment,
): AwsDatabaseSettingsResolver {

    override suspend fun resolve(
        databaseName: String,
        properties: AwsDatabaseConnectionProperties,
    ): AwsDatabaseConnectionProperties =
        properties
            .resolveFrom(properties.secretSource)
            .resolveFrom(properties.parameterSource)

    private fun AwsDatabaseConnectionProperties.resolveFrom(
        source: AwsDatabaseConfigSource?,
    ): AwsDatabaseConnectionProperties {
        if (source == null) {
            return this
        }
        val prefix = source.prefix?.trim()?.takeIf { it.isNotEmpty() }
            ?: return this

        val overlay = ConnectionOverlay(prefix)
        if (!overlay.hasAnyProperty()) {
            if (source.optional) {
                return this
            }
            throw IllegalStateException(
                "No Spring Environment properties found for ${source.type} source '${source.sourceId}' " +
                    "under prefix '$prefix'."
            )
        }

        return copy(
            url = overlay.string("url") ?: url,
            driverClassName = overlay.string("driver-class-name") ?: driverClassName,
            username = overlay.string("username") ?: username,
            password = overlay.string("password")?.let(AwsSecretString::of) ?: password,
            pool = overlay.pool(pool),
            dataSourceProperties = dataSourceProperties + overlay.map("data-source-properties"),
            metadata = metadata + overlay.map("metadata"),
            authenticationMode = overlay.enum<AwsDatabaseAuthenticationMode>("authentication-mode") ?: authenticationMode,
            rdsIam = overlay.rdsIam(rdsIam),
        )
    }

    private inner class ConnectionOverlay(private val prefix: String) {

        private val keyPrefix = "$prefix."

        fun hasAnyProperty(): Boolean =
            knownKeys.any { environment.containsProperty("$keyPrefix$it") } ||
                map("data-source-properties").isNotEmpty() ||
                map("metadata").isNotEmpty()

        fun string(name: String): String? =
            environment.getProperty("$keyPrefix$name")

        inline fun <reified T: Enum<T>> enum(name: String): T? =
            environment.getProperty("$keyPrefix$name", T::class.java)

        fun pool(current: AwsDatabasePoolProperties): AwsDatabasePoolProperties =
            current.copy(
                poolName = string("pool.pool-name") ?: current.poolName,
                maximumPoolSize = int("pool.maximum-pool-size") ?: current.maximumPoolSize,
                minimumIdle = int("pool.minimum-idle") ?: current.minimumIdle,
                connectionTimeoutMillis = long("pool.connection-timeout-millis") ?: current.connectionTimeoutMillis,
                idleTimeoutMillis = long("pool.idle-timeout-millis") ?: current.idleTimeoutMillis,
                maxLifetimeMillis = long("pool.max-lifetime-millis") ?: current.maxLifetimeMillis,
            )

        fun rdsIam(current: AwsRdsIamAuthenticationProperties?): AwsRdsIamAuthenticationProperties? {
            if (!rdsIamKeys.any { environment.containsProperty("$keyPrefix$it") }) {
                return current
            }
            return AwsRdsIamAuthenticationProperties(
                region = string("rds-iam.region") ?: current?.region ?: "",
                hostname = string("rds-iam.hostname") ?: current?.hostname ?: "",
                port = int("rds-iam.port") ?: current?.port ?: 0,
                username = string("rds-iam.username") ?: current?.username,
                tokenTtl = duration("rds-iam.token-ttl") ?: current?.tokenTtl
                ?: AwsRdsIamAuthenticationProperties.MAX_TOKEN_TTL,
                refreshBeforeExpiry = duration("rds-iam.refresh-before-expiry") ?: current?.refreshBeforeExpiry
                ?: AwsRdsIamAuthenticationProperties.DEFAULT_REFRESH_BEFORE_EXPIRY,
            )
        }

        fun map(name: String): Map<String, String> {
            val mapPrefix = "$keyPrefix$name."
            return enumerablePropertyNames()
                .filter { it.startsWith(mapPrefix) }
                .associate { propertyName ->
                    propertyName.removePrefix(mapPrefix) to environment.getProperty(propertyName).orEmpty()
                }
                .filterKeys { it.isNotBlank() }
        }

        private fun int(name: String): Int? =
            environment.getProperty("$keyPrefix$name", Int::class.java)

        private fun long(name: String): Long? =
            environment.getProperty("$keyPrefix$name", Long::class.java)

        private fun duration(name: String): Duration? =
            environment.getProperty("$keyPrefix$name", Duration::class.java)
    }

    private fun enumerablePropertyNames(): Sequence<String> {
        val configurable = environment as? ConfigurableEnvironment ?: return emptySequence()
        return configurable.propertySources.asSequence()
            .filterIsInstance<EnumerablePropertySource<*>>()
            .flatMap { it.propertyNames.asSequence() }
            .distinct()
    }

    private companion object {
        val rdsIamKeys = listOf(
            "rds-iam.region",
            "rds-iam.hostname",
            "rds-iam.port",
            "rds-iam.username",
            "rds-iam.token-ttl",
            "rds-iam.refresh-before-expiry",
        )

        val knownKeys = listOf(
            "url",
            "driver-class-name",
            "username",
            "password",
            "pool.pool-name",
            "pool.maximum-pool-size",
            "pool.minimum-idle",
            "pool.connection-timeout-millis",
            "pool.idle-timeout-millis",
            "pool.max-lifetime-millis",
            "authentication-mode",
        ) + rdsIamKeys
    }
}
