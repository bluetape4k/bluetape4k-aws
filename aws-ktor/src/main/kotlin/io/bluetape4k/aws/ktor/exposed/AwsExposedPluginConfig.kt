package io.bluetape4k.aws.ktor.exposed

import io.bluetape4k.aws.exposed.AwsDatabaseAuthenticationMode
import io.bluetape4k.aws.exposed.AwsDatabaseConfigSource
import io.bluetape4k.aws.exposed.AwsDatabaseConfigSourceType
import io.bluetape4k.aws.exposed.AwsDatabaseConnectionProperties
import io.bluetape4k.aws.exposed.AwsDatabasePoolProperties
import io.bluetape4k.aws.exposed.AwsDatabaseProperties
import io.bluetape4k.aws.exposed.AwsDatabaseSettingsResolver
import io.bluetape4k.aws.exposed.AwsExposedDatabaseFactory
import io.bluetape4k.aws.exposed.AwsExposedDatabaseRegistry
import io.bluetape4k.aws.exposed.AwsRdsIamAuthenticationProperties
import io.bluetape4k.aws.exposed.AwsSecretString
import io.bluetape4k.aws.exposed.NoopAwsDatabaseSettingsResolver
import io.bluetape4k.support.requireNotBlank
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [AwsExposedPlugin] 구성입니다.
 *
 * ## 계약
 *
 * [databaseProperties] 또는 DSL 메서드 [defaultDatabase]와 [database] 중 한 방식만 사용하세요.
 * 암묵적인 우선순위 규칙을 피하도록 두 구성 방식을 혼용하면 거부합니다. 생성된 진단 정보에서
 * 값이 노출되지 않도록 비밀번호 문자열을 [AwsSecretString]으로 변환합니다.
 *
 * ```kotlin
 * install(AwsExposedPlugin) {
 *     defaultDatabase {
 *         url = "jdbc:h2:mem:orders;MODE=PostgreSQL"
 *         driverClassName = "org.h2.Driver"
 *         username = "sa"
 *         password = "secret"
 *     }
 * }
 * ```
 */
class AwsExposedPluginConfig {

/** 경로 수준 Exposed JDBC suspend 트랜잭션이 사용하는 컨텍스트입니다. */
    var transactionContext: CoroutineContext = Dispatchers.IO

/** 플러그인 시작 시 레지스트리 생성에 허용되는 최대 시간입니다. */
    var startTimeout: Duration = 30.seconds

/** 플러그인 종료 시 레지스트리 닫기에 허용되는 최대 시간입니다. */
    var stopTimeout: Duration = 10.seconds

/** 공유 Exposed 팩토리가 데이터 소스를 생성하기 전에 사용하는 리졸버입니다. */
    var settingsResolver: AwsDatabaseSettingsResolver = NoopAwsDatabaseSettingsResolver

    private var explicitDatabaseProperties: AwsDatabaseProperties? = null
    private var defaultDatabaseConfig: AwsExposedConnectionConfig? = null
    private val namedDatabaseConfigs = linkedMapOf<String, AwsExposedConnectionConfig>()
    private var registryFactory:
        (suspend (AwsDatabaseProperties, AwsDatabaseSettingsResolver) -> AwsExposedDatabaseRegistry)? = null

    /**
     * Ktor DSL 대신 미리 구성된 공유 데이터베이스 속성을 사용합니다.
     */
    fun databaseProperties(properties: AwsDatabaseProperties) {
        require(defaultDatabaseConfig == null && namedDatabaseConfigs.isEmpty()) {
            "databaseProperties cannot be combined with defaultDatabase/database DSL configuration."
        }
        explicitDatabaseProperties = properties
    }

    /**
     * 기본 Exposed 데이터베이스를 구성합니다.
     */
    fun defaultDatabase(configure: AwsExposedConnectionConfig.() -> Unit) {
        require(explicitDatabaseProperties == null) {
            "defaultDatabase DSL cannot be combined with databaseProperties."
        }
        defaultDatabaseConfig = AwsExposedConnectionConfig().apply(configure)
    }

    /**
     * 이름이 지정된 Exposed 데이터베이스를 구성합니다.
     */
    fun database(
        name: String,
        configure: AwsExposedConnectionConfig.() -> Unit,
    ) {
        require(explicitDatabaseProperties == null) {
            "database DSL cannot be combined with databaseProperties."
        }
        name.requireNotBlank("name")
        require(name !in namedDatabaseConfigs) { "Database '$name' is already configured." }
        namedDatabaseConfigs[name] = AwsExposedConnectionConfig().apply(configure)
    }

    /**
     * 레지스트리 생성을 재정의합니다. 주로 테스트와 사용자 정의 [AwsExposedDatabaseFactory]가
     * 필요한 애플리케이션에서 유용합니다.
     */
    fun registryFactory(
        factory: suspend (AwsDatabaseProperties, AwsDatabaseSettingsResolver) -> AwsExposedDatabaseRegistry,
    ) {
        registryFactory = factory
    }

    internal fun toRuntimeConfig(): AwsExposedKtorRuntimeConfig {
        require(startTimeout.isPositive()) { "startTimeout must be positive." }
        require(stopTimeout.isPositive()) { "stopTimeout must be positive." }

        val properties = explicitDatabaseProperties ?: AwsDatabaseProperties(
            defaultDatabase = defaultDatabaseConfig?.toConnectionProperties()
                ?: AwsDatabaseConnectionProperties(),
            namedDatabases = namedDatabaseConfigs.mapValues { (_, config) -> config.toConnectionProperties() },
        )

        return AwsExposedKtorRuntimeConfig(
            databaseProperties = properties,
            registryFactory = registryFactory ?: { databaseProperties, resolver ->
                AwsExposedDatabaseFactory(resolver = resolver).createRegistry(databaseProperties)
            },
            settingsResolver = settingsResolver,
            transactionContext = transactionContext,
            startTimeout = startTimeout,
            stopTimeout = stopTimeout,
        )
    }
}

/**
 * [AwsDatabaseConnectionProperties] 값 하나를 위한 변경 가능한 Ktor DSL입니다.
 */
class AwsExposedConnectionConfig {

/** JDBC URL입니다. [secretSource] 또는 [parameterSource]에서 제공할 수 있습니다. */
    var url: String = ""

/** 선택적인 JDBC 드라이버 클래스 이름입니다. */
    var driverClassName: String? = null

/** 선택적인 JDBC 사용자 이름입니다. */
    var username: String? = null

/** 선택적인 정적 JDBC 비밀번호입니다. 변환 후에는 값이 가려진 상태로 표시됩니다. */
    var password: String? = null

/** 추가 Hikari 데이터 소스 속성입니다. */
    var dataSourceProperties: Map<String, String> = emptyMap()

/** 연결 속성과 함께 보존하는 호출자 메타데이터입니다. */
    var metadata: Map<String, String> = emptyMap()

/** 공유 Exposed 기반 계층이 사용하는 인증 모드입니다. */
    var authenticationMode: AwsDatabaseAuthenticationMode = AwsDatabaseAuthenticationMode.STATIC_PASSWORD

/** 선택적인 RDS IAM 인증 설정입니다. */
    var rdsIam: AwsRdsIamAuthenticationProperties? = null

    private val poolConfig = AwsExposedPoolConfig()
    private var secretSource: AwsDatabaseConfigSource? = null
    private var parameterSource: AwsDatabaseConfigSource? = null

    /**
     * Hikari 풀 설정을 구성합니다.
     */
    fun pool(configure: AwsExposedPoolConfig.() -> Unit) {
        poolConfig.configure()
    }

    /**
     * 이 데이터베이스에 AWS Secrets Manager 소스 설명자를 사용합니다.
     */
    fun secretSource(
        sourceId: String,
        configure: AwsExposedConfigSourceConfig.() -> Unit = {},
    ) {
        secretSource = AwsExposedConfigSourceConfig()
            .apply(configure)
            .toConfigSource(AwsDatabaseConfigSourceType.SECRETS_MANAGER, sourceId)
    }

    /**
     * 이 데이터베이스에 AWS Systems Manager Parameter Store 소스 설명자를 사용합니다.
     */
    fun parameterSource(
        sourceId: String,
        configure: AwsExposedConfigSourceConfig.() -> Unit = {},
    ) {
        parameterSource = AwsExposedConfigSourceConfig()
            .apply(configure)
            .toConfigSource(AwsDatabaseConfigSourceType.PARAMETER_STORE, sourceId)
    }

    internal fun toConnectionProperties(): AwsDatabaseConnectionProperties {
        driverClassName?.requireNotBlank("driverClassName")
        username?.requireNotBlank("username")
        val passwordValue = password?.also { it.requireNotBlank("password") }

        return AwsDatabaseConnectionProperties(
            url = url,
            driverClassName = driverClassName,
            username = username,
            password = passwordValue?.let(AwsSecretString::of),
            pool = poolConfig.toPoolProperties(),
            dataSourceProperties = dataSourceProperties,
            metadata = metadata,
            secretSource = secretSource,
            parameterSource = parameterSource,
            authenticationMode = authenticationMode,
            rdsIam = rdsIam,
        )
    }
}

/**
 * [AwsDatabasePoolProperties]용 변경 가능한 Ktor DSL입니다.
 */
class AwsExposedPoolConfig {

    var poolName: String? = null
    var maximumPoolSize: Int = 10
    var minimumIdle: Int = 1
    var connectionTimeoutMillis: Long = 30_000L
    var idleTimeoutMillis: Long = 600_000L
    var maxLifetimeMillis: Long = 1_800_000L

    internal fun toPoolProperties(): AwsDatabasePoolProperties =
        AwsDatabasePoolProperties(
            poolName = poolName,
            maximumPoolSize = maximumPoolSize,
            minimumIdle = minimumIdle,
            connectionTimeoutMillis = connectionTimeoutMillis,
            idleTimeoutMillis = idleTimeoutMillis,
            maxLifetimeMillis = maxLifetimeMillis,
        )
}

/**
 * [AwsDatabaseConfigSource]용 변경 가능한 Ktor DSL입니다.
 */
class AwsExposedConfigSourceConfig {

/** 원격 값을 매핑할 때 리졸버가 사용하는 선택적인 키 접두사입니다. */
    var prefix: String? = null

/** 리졸버가 누락된 원격 소스 값을 무시할지 여부입니다. */
    var optional: Boolean = false

    internal fun toConfigSource(
        type: AwsDatabaseConfigSourceType,
        sourceId: String,
    ): AwsDatabaseConfigSource {
        sourceId.requireNotBlank("sourceId")
        prefix?.requireNotBlank("prefix")

        return AwsDatabaseConfigSource(
            type = type,
            sourceId = sourceId,
            prefix = prefix,
            optional = optional,
        )
    }
}
