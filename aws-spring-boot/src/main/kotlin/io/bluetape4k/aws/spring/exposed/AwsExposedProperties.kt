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
 * AWS 기반 Exposed database를 위한 Spring Boot configuration properties입니다.
 *
 * ## 계약
 *
 * `bluetape4k.aws.exposed`를 bind하고 Spring configuration model을 `bluetape4k-aws-exposed`가 사용하는
 * framework-neutral [AwsDatabaseProperties]로 변환합니다.
 */
@ConfigurationProperties(prefix = "bluetape4k.aws.exposed")
data class AwsExposedProperties(
    /** AWS Exposed auto-configuration을 활성화할지 나타냅니다. 기본값은 `true`입니다. */
    val enabled: Boolean = true,
    /** 기본 Exposed database 연결 설정입니다. */
    val defaultDatabase: AwsExposedConnectionProperties = AwsExposedConnectionProperties(),
    /** 이름으로 구분되는 추가 Exposed database 연결 설정입니다. */
    val namedDatabases: Map<String, AwsExposedConnectionProperties> = emptyMap(),
): Serializable {

    /**
     * Spring-bound properties를 공통 AWS Exposed model로 변환합니다.
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
 * AWS 기반 Exposed database 하나에 대한 Spring-bindable JDBC 연결 설정입니다.
 */
data class AwsExposedConnectionProperties(
    /** JDBC URL입니다. 실제 registry 생성 시 비어 있으면 안 됩니다. */
    val url: String = "",
    /** 선택적 JDBC driver class name입니다. 지정하면 classpath에서 로드 가능해야 합니다. */
    val driverClassName: String? = null,
    /** JDBC username입니다. RDS IAM 모드에서는 token username의 fallback으로도 사용됩니다. */
    val username: String? = null,
    /** static password 모드에서 사용할 평문 bind 값입니다. 공통 model 변환 시 [AwsSecretString]으로 감쌉니다. */
    val password: String? = null,
    /** HikariCP pool 동작을 제어하는 설정입니다. */
    val pool: AwsExposedPoolProperties = AwsExposedPoolProperties(),
    /** JDBC driver 또는 DataSource로 전달할 추가 속성입니다. */
    val dataSourceProperties: Map<String, String> = emptyMap(),
    /** application이 연결 설정에 붙일 수 있는 부가 metadata입니다. */
    val metadata: Map<String, String> = emptyMap(),
    /** Secrets Manager 기반 설정 overlay descriptor입니다. */
    val secretSource: AwsExposedConfigSource? = null,
    /** Parameter Store 기반 설정 overlay descriptor입니다. */
    val parameterSource: AwsExposedConfigSource? = null,
    /** physical JDBC connection을 열 때 사용할 인증 방식입니다. */
    val authenticationMode: AwsDatabaseAuthenticationMode = AwsDatabaseAuthenticationMode.STATIC_PASSWORD,
    /** [AwsDatabaseAuthenticationMode.RDS_IAM] 인증을 사용할 때 필요한 token 설정입니다. */
    val rdsIam: AwsExposedRdsIamAuthenticationProperties? = null,
): Serializable {

    /**
     * Spring-bound settings를 공통 AWS Exposed model로 변환합니다.
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
 * Spring-bindable Hikari pool 설정입니다.
 */
data class AwsExposedPoolProperties(
    /** 명시적 pool name입니다. `null`이면 공통 factory가 database name으로 기본 이름을 생성합니다. */
    val poolName: String? = null,
    /** pool이 열 수 있는 최대 connection 수입니다. */
    val maximumPoolSize: Int = 10,
    /** pool이 유지할 최소 idle connection 수입니다. */
    val minimumIdle: Int = 1,
    /** connection 획득을 기다릴 최대 시간 ms 단위 값입니다. */
    val connectionTimeoutMillis: Long = 30_000L,
    /** idle connection을 유지할 시간 ms 단위 값입니다. */
    val idleTimeoutMillis: Long = 600_000L,
    /** connection 최대 수명 ms 단위 값입니다. */
    val maxLifetimeMillis: Long = 1_800_000L,
): Serializable {

    /**
     * Spring-bound settings를 공통 AWS Exposed pool model로 변환합니다.
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
 * Spring-bindable remote configuration source descriptor입니다.
 */
data class AwsExposedConfigSource(
    /** secret id, parameter path 등 backend에서 source를 식별하는 값입니다. */
    val sourceId: String = "",
    /** source 안에서 database 설정 key를 구분할 선택적 prefix입니다. */
    val prefix: String? = null,
    /** source가 없거나 비어 있어도 오류로 처리하지 않을지 나타냅니다. */
    val optional: Boolean = false,
): Serializable {

    /**
     * 이 descriptor를 공통 AWS Exposed source model로 변환합니다.
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
 * Spring-bindable Amazon RDS IAM authentication 설정입니다.
 */
data class AwsExposedRdsIamAuthenticationProperties(
    /** token signing에 사용할 AWS region 이름입니다. */
    val region: String = "",
    /** token signing 대상 RDS endpoint hostname입니다. */
    val hostname: String = "",
    /** RDS endpoint port입니다. */
    val port: Int = 0,
    /** token에 포함할 database username입니다. `null`이면 connection username을 사용합니다. */
    val username: String? = null,
    /** 생성된 token의 유효 시간입니다. */
    val tokenTtl: Duration = AwsRdsIamAuthenticationProperties.MAX_TOKEN_TTL,
    /** token 만료 전에 새 token을 만들 refresh 여유 시간입니다. */
    val refreshBeforeExpiry: Duration = AwsRdsIamAuthenticationProperties.DEFAULT_REFRESH_BEFORE_EXPIRY,
): Serializable {

    /**
     * Spring-bound settings를 공통 AWS Exposed RDS IAM model로 변환합니다.
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
