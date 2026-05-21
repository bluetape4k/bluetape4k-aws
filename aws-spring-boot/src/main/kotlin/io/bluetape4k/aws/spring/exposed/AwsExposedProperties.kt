package io.bluetape4k.aws.spring.exposed

import io.bluetape4k.aws.exposed.AwsDatabaseAuthenticationMode
import io.bluetape4k.aws.exposed.AwsDatabaseConfigSource
import io.bluetape4k.aws.exposed.AwsDatabaseConfigSourceType
import io.bluetape4k.aws.exposed.AwsDatabaseConnectionProperties
import io.bluetape4k.aws.exposed.AwsDatabasePoolProperties
import io.bluetape4k.aws.exposed.AwsDatabaseProperties
import io.bluetape4k.aws.exposed.AwsRdsIamAuthenticationProperties
import io.bluetape4k.aws.exposed.AwsSecretString
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.time.Duration

/**
 * Spring Boot configuration properties for AWS-backed Exposed databases.
 *
 * ## Contract
 *
 * Binds `bluetape4k.aws.exposed` and adapts the Spring configuration model to
 * the framework-neutral [AwsDatabaseProperties] used by
 * `bluetape4k-aws-exposed`.
 */
@ConfigurationProperties(prefix = "bluetape4k.aws.exposed")
data class AwsExposedProperties(
    val enabled: Boolean = true,
    val defaultDatabase: AwsExposedConnectionProperties = AwsExposedConnectionProperties(),
    val namedDatabases: Map<String, AwsExposedConnectionProperties> = emptyMap(),
): Serializable {

    /**
     * Converts Spring-bound properties to the common AWS Exposed model.
     */
    fun toDatabaseProperties(): AwsDatabaseProperties =
        AwsDatabaseProperties(
            defaultDatabase = defaultDatabase.toConnectionProperties(),
            namedDatabases = namedDatabases.mapValues { (_, properties) -> properties.toConnectionProperties() },
        )

    companion object {
        private const val serialVersionUID: Long = -7800244022257619813L
    }
}

/**
 * Spring-bindable JDBC connection settings for one AWS-backed Exposed database.
 */
data class AwsExposedConnectionProperties(
    val url: String = "",
    val driverClassName: String? = null,
    val username: String? = null,
    val password: String? = null,
    val pool: AwsExposedPoolProperties = AwsExposedPoolProperties(),
    val dataSourceProperties: Map<String, String> = emptyMap(),
    val metadata: Map<String, String> = emptyMap(),
    val secretSource: AwsExposedConfigSource? = null,
    val parameterSource: AwsExposedConfigSource? = null,
    val authenticationMode: AwsDatabaseAuthenticationMode = AwsDatabaseAuthenticationMode.STATIC_PASSWORD,
    val rdsIam: AwsExposedRdsIamAuthenticationProperties? = null,
): Serializable {

    /**
     * Converts Spring-bound settings to the common AWS Exposed model.
     */
    fun toConnectionProperties(): AwsDatabaseConnectionProperties =
        AwsDatabaseConnectionProperties(
            url = url,
            driverClassName = driverClassName,
            username = username,
            password = password?.let(AwsSecretString::of),
            pool = pool.toPoolProperties(),
            dataSourceProperties = dataSourceProperties,
            metadata = metadata,
            secretSource = secretSource?.toConfigSource(AwsDatabaseConfigSourceType.SECRETS_MANAGER),
            parameterSource = parameterSource?.toConfigSource(AwsDatabaseConfigSourceType.PARAMETER_STORE),
            authenticationMode = authenticationMode,
            rdsIam = rdsIam?.toRdsIamAuthenticationProperties(),
        )

    companion object {
        private const val serialVersionUID: Long = -338054701106841556L
    }
}

/**
 * Spring-bindable Hikari pool settings.
 */
data class AwsExposedPoolProperties(
    val poolName: String? = null,
    val maximumPoolSize: Int = 10,
    val minimumIdle: Int = 1,
    val connectionTimeoutMillis: Long = 30_000L,
    val idleTimeoutMillis: Long = 600_000L,
    val maxLifetimeMillis: Long = 1_800_000L,
): Serializable {

    /**
     * Converts Spring-bound settings to the common AWS Exposed pool model.
     */
    fun toPoolProperties(): AwsDatabasePoolProperties =
        AwsDatabasePoolProperties(
            poolName = poolName,
            maximumPoolSize = maximumPoolSize,
            minimumIdle = minimumIdle,
            connectionTimeoutMillis = connectionTimeoutMillis,
            idleTimeoutMillis = idleTimeoutMillis,
            maxLifetimeMillis = maxLifetimeMillis,
        )

    companion object {
        private const val serialVersionUID: Long = -4776049056636061974L
    }
}

/**
 * Spring-bindable remote configuration source descriptor.
 */
data class AwsExposedConfigSource(
    val sourceId: String = "",
    val prefix: String? = null,
    val optional: Boolean = false,
): Serializable {

    /**
     * Converts this descriptor to the common AWS Exposed source model.
     */
    fun toConfigSource(type: AwsDatabaseConfigSourceType): AwsDatabaseConfigSource =
        AwsDatabaseConfigSource(
            type = type,
            sourceId = sourceId,
            prefix = prefix,
            optional = optional,
        )

    companion object {
        private const val serialVersionUID: Long = -1344613893909889491L
    }
}

/**
 * Spring-bindable Amazon RDS IAM authentication settings.
 */
data class AwsExposedRdsIamAuthenticationProperties(
    val region: String = "",
    val hostname: String = "",
    val port: Int = 0,
    val username: String? = null,
    val tokenTtl: Duration = AwsRdsIamAuthenticationProperties.MAX_TOKEN_TTL,
    val refreshBeforeExpiry: Duration = AwsRdsIamAuthenticationProperties.DEFAULT_REFRESH_BEFORE_EXPIRY,
): Serializable {

    /**
     * Converts Spring-bound settings to the common AWS Exposed RDS IAM model.
     */
    fun toRdsIamAuthenticationProperties(): AwsRdsIamAuthenticationProperties =
        AwsRdsIamAuthenticationProperties(
            region = region,
            hostname = hostname,
            port = port,
            username = username,
            tokenTtl = tokenTtl,
            refreshBeforeExpiry = refreshBeforeExpiry,
        )

    companion object {
        private const val serialVersionUID: Long = -7078158753577801792L
    }
}
