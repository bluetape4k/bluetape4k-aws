package io.bluetape4k.aws.exposed

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable


data class AwsDatabaseProperties(
    val defaultDatabase: AwsDatabaseConnectionProperties = AwsDatabaseConnectionProperties(),
    val namedDatabases: Map<String, AwsDatabaseConnectionProperties> = emptyMap(),
): Serializable {

    init {
        namedDatabases.keys.forEach { it.requireNotBlank("namedDatabases key") }
    }

    companion object: KLogging() {
        private const val serialVersionUID: Long = 4711399148814050012L
    }
}

/**
 * JDBC connection settings for one AWS-backed Exposed database.
 *
 * [password] is wrapped in [AwsSecretString] so generated diagnostics and data
 * class `toString()` output do not reveal the secret value. Use
 * [authenticationMode] and [rdsIam] when the JDBC password must be generated as
 * an Amazon RDS IAM authentication token.
 */
data class AwsDatabaseConnectionProperties(
    val url: String = "",
    val driverClassName: String? = null,
    val username: String? = null,
    val password: AwsSecretString? = null,
    val pool: AwsDatabasePoolProperties = AwsDatabasePoolProperties(),
    val dataSourceProperties: Map<String, String> = emptyMap(),
    val metadata: Map<String, String> = emptyMap(),
    val secretSource: AwsDatabaseConfigSource? = null,
    val parameterSource: AwsDatabaseConfigSource? = null,
    val authenticationMode: AwsDatabaseAuthenticationMode = AwsDatabaseAuthenticationMode.STATIC_PASSWORD,
    val rdsIam: AwsRdsIamAuthenticationProperties? = null,
): Serializable {

    init {
        driverClassName?.requireNotBlank("driverClassName")
        username?.requireNotBlank("username")
        when (authenticationMode) {
            AwsDatabaseAuthenticationMode.STATIC_PASSWORD -> {
                require(rdsIam == null) { "rdsIam must be null when authenticationMode is STATIC_PASSWORD." }
            }
            AwsDatabaseAuthenticationMode.RDS_IAM         -> {
                require(password == null) { "password must be null when authenticationMode is RDS_IAM." }
                val iam = requireNotNull(rdsIam) { "rdsIam must be configured when authenticationMode is RDS_IAM." }
                iam.effectiveUsername(username)
            }
        }
        dataSourceProperties.keys.forEach { it.requireNotBlank("dataSourceProperties key") }
        metadata.keys.forEach { it.requireNotBlank("metadata key") }
    }

    companion object: KLogging() {
        private const val serialVersionUID: Long = 2904515010393731394L
    }
}

/**
 * Hikari pool settings used by [AwsExposedDatabaseFactory].
 */
data class AwsDatabasePoolProperties(
    val poolName: String? = null,
    val maximumPoolSize: Int = 10,
    val minimumIdle: Int = 1,
    val connectionTimeoutMillis: Long = 30_000L,
    val idleTimeoutMillis: Long = 600_000L,
    val maxLifetimeMillis: Long = 1_800_000L,
): Serializable {

    init {
        poolName?.requireNotBlank("poolName")
        maximumPoolSize.requirePositiveNumber("maximumPoolSize")
        minimumIdle.requireGe(0, "minimumIdle")
        require(minimumIdle <= maximumPoolSize) {
            "minimumIdle must be less than or equal to maximumPoolSize: $minimumIdle > $maximumPoolSize"
        }
        connectionTimeoutMillis.requirePositiveNumber("connectionTimeoutMillis")
        idleTimeoutMillis.requireGe(0, "idleTimeoutMillis")
        maxLifetimeMillis.requirePositiveNumber("maxLifetimeMillis")
    }

    companion object: KLogging() {
        private const val serialVersionUID: Long = -3715261391871650193L
    }
}
