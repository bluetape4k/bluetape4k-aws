package io.bluetape4k.aws.ktor.exposed

import io.bluetape4k.exposed.ktor.core.ExposedKtorCooperativeReadinessProbe
import io.bluetape4k.exposed.ktor.core.ExposedKtorReadinessBackend
import io.bluetape4k.exposed.ktor.core.ExposedKtorReadinessOutcome
import io.bluetape4k.exposed.ktor.core.bluetape4kExposedHealthRoutes
import io.bluetape4k.exposed.ktor.jdbc.exposedKtorJdbcReadinessProbe
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [AwsExposedPlugin]이 관리하는 JDBC database를 위한 선택적 health/readiness route 설정입니다.
 *
 * `AwsExposedPlugin`은 application 설정 단계에서 아직 registry를 시작하지 않았으므로
 * readiness probe는 [Database]를 설치 시점에 조회하지 않습니다. 요청 시점에 runtime에서
 * handle을 해석하고, 호출자가 제공한 blocking dispatcher로 `SELECT 1`을 실행합니다.
 */
data class AwsExposedKtorHealthConfig(
    val databaseName: String? = null,
    val component: String = DEFAULT_JDBC_COMPONENT,
    val blockingDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val healthPath: String = DEFAULT_HEALTH_PATH,
    val readinessPath: String = DEFAULT_READINESS_PATH,
    val readinessProbeTimeout: Duration = DEFAULT_READINESS_PROBE_TIMEOUT,
    val jdbcQueryTimeout: Duration = DEFAULT_JDBC_QUERY_TIMEOUT,
    val meterRegistry: MeterRegistry? = null,
) {

    init {
        databaseName?.requireValidDatabaseName()
        component.requireValidComponent()
        readinessProbeTimeout.requireFinitePositive("readinessProbeTimeout")
        jdbcQueryTimeout.requireFinitePositive("jdbcQueryTimeout")
    }

    companion object {
        const val DEFAULT_JDBC_COMPONENT: String = "jdbc"
        const val DEFAULT_HEALTH_PATH: String = "/healthz/exposed"
        const val DEFAULT_READINESS_PATH: String = "/readyz/exposed"
        val DEFAULT_READINESS_PROBE_TIMEOUT: Duration = 1.seconds
        val DEFAULT_JDBC_QUERY_TIMEOUT: Duration = 1.seconds
    }
}

/**
 * AWS Exposed registry를 사용하는 JDBC liveness/readiness route를 명시적으로 설치합니다.
 *
 * 이 함수는 database, pool, dispatcher 또는 registry를 생성하거나 닫지 않습니다. 따라서
 * `AwsExposedPlugin` 설치 이후 호출해야 하며, route를 설치하는 것만으로는 기존 주문 route가
 * 변경되지 않습니다.
 */
fun Application.installAwsExposedHealthRoutes(
    config: AwsExposedKtorHealthConfig = AwsExposedKtorHealthConfig(),
) {
    val runtime = awsExposed()
    val probe = AwsExposedJdbcReadinessProbe(runtime, config)

    routing {
        bluetape4kExposedHealthRoutes(
            probes = listOf(probe),
            healthPath = config.healthPath,
            readinessPath = config.readinessPath,
            readinessProbeTimeout = config.readinessProbeTimeout,
            meterRegistry = config.meterRegistry,
        )
    }
}

private class AwsExposedJdbcReadinessProbe(
    private val runtime: AwsExposedKtorRuntime,
    private val config: AwsExposedKtorHealthConfig,
) : ExposedKtorCooperativeReadinessProbe {

    override val component: String = config.component
    override val backend: ExposedKtorReadinessBackend = ExposedKtorReadinessBackend.JDBC

    override suspend fun probe(timeout: Duration): ExposedKtorReadinessOutcome =
        exposedKtorJdbcReadinessProbe(
            db = runtime.database(config.databaseName),
            blockingDispatcher = config.blockingDispatcher,
            jdbcQueryTimeout = config.jdbcQueryTimeout,
            component = config.component,
        ).probe(timeout)
}

private val COMPONENT_PATTERN = Regex("[a-z][a-z0-9_.-]{0,62}")

private fun String.requireValidComponent() {
    require(COMPONENT_PATTERN.matches(this)) {
        "Invalid AWS Exposed health component: reason=unsafe_component."
    }
}

private fun String.requireValidDatabaseName() {
    require(isNotBlank()) { "databaseName must not be blank." }
}

private fun Duration.requireFinitePositive(parameterName: String) {
    require(isFinite() && isPositive()) {
        "$parameterName must be finite and positive."
    }
}
