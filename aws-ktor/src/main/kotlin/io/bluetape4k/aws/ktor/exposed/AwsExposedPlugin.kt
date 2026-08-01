package io.bluetape4k.aws.ktor.exposed

import io.bluetape4k.aws.exposed.AwsExposedDatabaseHandle
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import kotlin.coroutines.CoroutineContext

/**
 * 설치된 [AwsExposedKtorRuntime]을 저장하는 애플리케이션 속성 키입니다.
 */
val AwsExposedKtorRuntimeKey: AttributeKey<AwsExposedKtorRuntime> = AttributeKey("AwsExposedKtorRuntime")

/**
 * AWS 기반 Exposed JDBC 데이터베이스용 Ktor 애플리케이션 플러그인입니다.
 *
 * ## 계약
 *
 * 플러그인은 애플리케이션 시작 시 공유 [AwsExposedKtorRuntime]을 생성해 애플리케이션 속성에 저장하고,
 * 애플리케이션 중지 시 레지스트리를 닫습니다. Ktor 수명 주기 훅은 동기식이므로 시작과 종료 시
 * 제한 시간이 있는 `runBlocking(Dispatchers.IO)`으로 suspend 데이터베이스 작업을 연결합니다.
 */
val AwsExposedPlugin: ApplicationPlugin<AwsExposedPluginConfig> = createApplicationPlugin(
    name = "AwsExposedPlugin",
    createConfiguration = ::AwsExposedPluginConfig,
) {
    val runtime = AwsExposedKtorRuntime(pluginConfig.toRuntimeConfig())
    application.attributes.put(AwsExposedKtorRuntimeKey, runtime)

    on(MonitoringEvent(ApplicationStarted)) {
        // Ktor monitoring event는 동기식이지만 registry 생성은 suspend이며 blocking JDBC pool을 초기화할 수 있다.
        runBlocking(Dispatchers.IO) {
            runtime.start()
        }
    }
    on(MonitoringEvent(ApplicationStopping)) {
        // Ktor monitoring event는 동기식이며 registry 종료는 blocking JDBC resource를 닫는다.
        runBlocking(Dispatchers.IO) {
            runtime.stop()
        }
    }
}

/**
 * [AwsExposedPlugin]이 설치한 런타임을 반환합니다.
 *
 * @throws IllegalStateException [AwsExposedPlugin]이 설치되지 않은 경우
 */
fun Application.awsExposed(): AwsExposedKtorRuntime =
    attributes.getOrNull(AwsExposedKtorRuntimeKey)
        ?: throw IllegalStateException("AwsExposedPlugin is not installed.")

/**
 * 이 호출에 대해 [AwsExposedPlugin]이 설치한 런타임을 반환합니다.
 *
 * @throws IllegalStateException [AwsExposedPlugin]이 설치되지 않은 경우
 */
fun ApplicationCall.awsExposed(): AwsExposedKtorRuntime =
    application.awsExposed()

/**
 * 기본 또는 이름이 지정된 AWS 기반 Exposed 데이터베이스 핸들을 반환합니다.
 *
 * @throws IllegalStateException [AwsExposedPlugin]이 설치되지 않았거나 시작되지 않은 경우
 */
fun Application.awsExposedHandle(name: String? = null): AwsExposedDatabaseHandle =
    awsExposed().handle(name)

/**
 * 이 호출에 대해 기본 또는 이름이 지정된 AWS 기반 Exposed 데이터베이스 핸들을 반환합니다.
 *
 * @throws IllegalStateException [AwsExposedPlugin]이 설치되지 않았거나 시작되지 않은 경우
 */
fun ApplicationCall.awsExposedHandle(name: String? = null): AwsExposedDatabaseHandle =
    awsExposed().handle(name)

/**
 * 기본 또는 이름이 지정된 Exposed [Database]를 반환합니다.
 *
 * @throws IllegalStateException [AwsExposedPlugin]이 설치되지 않았거나 시작되지 않은 경우
 */
fun Application.awsExposedDatabase(name: String? = null): Database =
    awsExposed().database(name)

/**
 * 이 호출에 대해 기본 또는 이름이 지정된 Exposed [Database]를 반환합니다.
 *
 * @throws IllegalStateException [AwsExposedPlugin]이 설치되지 않았거나 시작되지 않은 경우
 */
fun ApplicationCall.awsExposedDatabase(name: String? = null): Database =
    awsExposed().database(name)

/**
 * Exposed JDBC suspend 트랜잭션 안에서 [statement]를 실행합니다.
 *
 * @throws IllegalStateException [AwsExposedPlugin]이 설치되지 않았거나 시작되지 않은 경우
 */
suspend fun <T> Application.awsExposedTransaction(
    name: String? = null,
    context: CoroutineContext? = null,
    statement: suspend JdbcTransaction.() -> T,
): T =
    if (context == null) {
        awsExposed().transaction(name = name, statement = statement)
    } else {
        awsExposed().transaction(name = name, context = context, statement = statement)
    }

/**
 * 이 호출에 대해 Exposed JDBC suspend 트랜잭션 안에서 [statement]를 실행합니다.
 *
 * @throws IllegalStateException [AwsExposedPlugin]이 설치되지 않았거나 시작되지 않은 경우
 */
suspend fun <T> ApplicationCall.awsExposedTransaction(
    name: String? = null,
    context: CoroutineContext? = null,
    statement: suspend JdbcTransaction.() -> T,
): T =
    if (context == null) {
        awsExposed().transaction(name = name, statement = statement)
    } else {
        awsExposed().transaction(name = name, context = context, statement = statement)
    }
