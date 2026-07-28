package io.bluetape4k.aws.exposed

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable


/**
 * 기본 handle과 선택적 named handle에 사용할 Exposed database 연결 설정입니다.
 *
 * ## 계약
 *
 * [defaultDatabase]는 [AwsExposedDatabaseFactory.DEFAULT_DATABASE_NAME] 이름으로 등록됩니다.
 * [namedDatabases] key는 비어 있으면 안 되며 예약된 기본 handle 이름을 사용할 수 없습니다.
 */
data class AwsDatabaseProperties(
    /** 기본 database handle을 만들 때 사용할 연결 설정입니다. */
    val defaultDatabase: AwsDatabaseConnectionProperties = AwsDatabaseConnectionProperties(),
    /** 이름으로 구분되는 추가 database handle 설정입니다. key는 registry 조회 이름으로 사용됩니다. */
    val namedDatabases: Map<String, AwsDatabaseConnectionProperties> = emptyMap(),
): Serializable {

    init {
        namedDatabases.keys.forEach { key ->
            val databaseName = key.requireNotBlank("namedDatabases key")
            require(databaseName != AwsExposedDatabaseFactory.DEFAULT_DATABASE_NAME) {
                "namedDatabases key '${AwsExposedDatabaseFactory.DEFAULT_DATABASE_NAME}' is reserved for defaultDatabase."
            }
        }
    }

    companion object: KLogging() {
        private const val serialVersionUID: Long = 4711399148814050012L
    }
}

/**
 * AWS 기반 Exposed database 하나에 대한 JDBC 연결 설정입니다.
 *
 * [password]는 [AwsSecretString]으로 감싸 진단 출력과 data class `toString()`이 secret 값을 노출하지 않게 합니다.
 * JDBC password를 Amazon RDS IAM authentication token으로 생성해야 하면 [authenticationMode]와 [rdsIam]을 사용합니다.
 */
data class AwsDatabaseConnectionProperties(
    /** JDBC URL입니다. database handle 생성 시 비어 있으면 안 됩니다. */
    val url: String = "",
    /** 선택적 JDBC driver class name입니다. 지정하면 classpath에서 로드 가능해야 합니다. */
    val driverClassName: String? = null,
    /** JDBC username입니다. RDS IAM 모드에서는 token signing username의 fallback으로도 사용됩니다. */
    val username: String? = null,
    /** static password 모드에서 사용할 secret-safe JDBC password입니다. */
    val password: AwsSecretString? = null,
    /** HikariCP pool 동작을 제어하는 설정입니다. */
    val pool: AwsDatabasePoolProperties = AwsDatabasePoolProperties(),
    /** JDBC driver 또는 DataSource로 전달할 추가 속성입니다. key는 비어 있으면 안 됩니다. */
    val dataSourceProperties: Map<String, String> = emptyMap(),
    /** application이 연결 설정에 붙일 수 있는 부가 metadata입니다. key는 비어 있으면 안 됩니다. */
    val metadata: Map<String, String> = emptyMap(),
    /** Secrets Manager에서 연결 설정을 가져올 때 사용할 선택적 descriptor입니다. */
    val secretSource: AwsDatabaseConfigSource? = null,
    /** Parameter Store에서 연결 설정을 가져올 때 사용할 선택적 descriptor입니다. */
    val parameterSource: AwsDatabaseConfigSource? = null,
    /** physical JDBC connection을 열 때 사용할 인증 방식입니다. */
    val authenticationMode: AwsDatabaseAuthenticationMode = AwsDatabaseAuthenticationMode.STATIC_PASSWORD,
    /** [AwsDatabaseAuthenticationMode.RDS_IAM] 모드에서 token 생성을 제어하는 설정입니다. */
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
 * [AwsExposedDatabaseFactory]가 사용하는 Hikari pool 설정입니다.
 */
data class AwsDatabasePoolProperties(
    /** 명시적 pool name입니다. `null`이면 factory가 database name으로 기본 이름을 생성합니다. */
    val poolName: String? = null,
    /** pool이 열 수 있는 최대 connection 수입니다. 양수여야 합니다. */
    val maximumPoolSize: Int = 10,
    /** pool이 유지할 최소 idle connection 수입니다. `0` 이상이며 [maximumPoolSize]보다 클 수 없습니다. */
    val minimumIdle: Int = 1,
    /** connection 획득을 기다릴 최대 시간 ms 단위 값입니다. 양수여야 합니다. */
    val connectionTimeoutMillis: Long = 30_000L,
    /** idle connection을 유지할 시간 ms 단위 값입니다. `0`이면 Hikari idle timeout을 비활성화합니다. */
    val idleTimeoutMillis: Long = 600_000L,
    /** connection 최대 수명 ms 단위 값입니다. 양수여야 합니다. */
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
