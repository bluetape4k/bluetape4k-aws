package io.bluetape4k.aws.ktor.dynamodb

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.ResourceInUseException
import io.bluetape4k.aws.kotlin.dynamodb.DynamoItemMapper
import io.bluetape4k.aws.kotlin.dynamodb.DynamoItemReader
import io.bluetape4k.aws.kotlin.dynamodb.createTable
import io.bluetape4k.aws.kotlin.dynamodb.existsTable
import io.bluetape4k.aws.kotlin.dynamodb.waitForTableReady
import io.bluetape4k.ktor.core.ApplicationResourceRegistry
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [DynamoDbKtorRuntime]의 런타임 구성입니다.
 */
class DynamoDbKtorRuntimeConfig(
    val dynamoDbClient: DynamoDbClient,
    val ownsClient: Boolean,
    val autoCreateTables: Boolean = false,
    val tableDefinitions: List<DynamoDbKtorTableDefinition> = emptyList(),
    val tableReadyTimeout: Duration = 60.seconds,
    val closeTimeout: Duration = 10.seconds,
)

/**
 * [DynamoDbKtorPlugin]이 설치하는 런타임입니다.
 *
 * 계약:
 * - [start]는 [DynamoDbKtorRuntimeConfig.autoCreateTables]가 true일 때만 명시적으로 등록한 누락 테이블을 생성합니다.
 * - [registerApplicationResources]는 플러그인이 소유한 클라이언트만 공통 application lifecycle registry에 등록합니다.
 * - [stop]은 직접 호출할 때 플러그인이 소유한 클라이언트만 닫으며, 중복 close를 방지합니다.
 * - [repository]는 같은 애플리케이션 범위 AWS Kotlin SDK 클라이언트 위에 경량 리포지토리 파사드를 생성합니다.
 */
class DynamoDbKtorRuntime(
    private val config: DynamoDbKtorRuntimeConfig,
) {
    companion object: KLogging()

    private val closeStarted = AtomicBoolean(false)
    private val resourceRegistrationInstalled = AtomicBoolean(false)
    private val ownedClientResource = AutoCloseable {
        runBlocking(Dispatchers.IO) {
            stop()
        }
    }

    val dynamoDbClient: DynamoDbClient
        get() = config.dynamoDbClient

    suspend fun start() {
        if (!config.autoCreateTables) {
            return
        }

        config.tableDefinitions.forEach { table ->
            createTableIfMissing(table)
        }
    }

    /**
     * 플러그인 소유 클라이언트를 공통 application lifecycle registry에 등록합니다.
     * registry는 `ApplicationStopped`에서 동기적으로 close action을 실행하므로, 이 메서드는
     * AWS adapter가 소유한 timeout과 dispatcher bridge를 close action 안에 유지합니다.
     */
    internal fun registerApplicationResources(registry: ApplicationResourceRegistry) {
        if (!config.ownsClient || !resourceRegistrationInstalled.compareAndSet(false, true)) {
            return
        }

        registry.register(ownedClientResource)
    }

    suspend fun stop() {
        if (!config.ownsClient || !closeStarted.compareAndSet(false, true)) {
            return
        }

        val closed = withTimeoutOrNull(config.closeTimeout) {
            runInterruptible(Dispatchers.IO) {
                config.dynamoDbClient.close()
            }
            true
        } ?: false

        if (!closed) {
            log.warn { "Timed out while closing plugin-owned DynamoDB client after ${config.closeTimeout}." }
        }
    }

    /**
     * [tableName]에 바인딩된 DynamoDB 리포지토리를 생성합니다.
     */
    fun <T: Any, K: Any> repository(
        tableName: String,
        mapper: DynamoItemMapper<T>,
        reader: DynamoItemReader<T>,
        keyMapper: DynamoItemMapper<K>,
    ): DynamoDbKtorRepository<T, K> =
        DynamoDbKtorRepository(
            dynamoDbClient = config.dynamoDbClient,
            tableName = tableName,
            mapper = mapper,
            reader = reader,
            keyMapper = keyMapper,
        )

    private suspend fun createTableIfMissing(table: DynamoDbKtorTableDefinition) {
        val exists = config.dynamoDbClient.existsTable(table.tableName)
        if (exists) {
            log.debug { "DynamoDB table[${table.tableName}] already exists. Skipping auto-creation." }
        } else {
            try {
                config.dynamoDbClient.createTable(
                    tableName = table.tableName,
                    keySchema = table.keySchema,
                    attributeDefinitions = table.attributeDefinitions,
                    readCapacityUnits = table.readCapacityUnits,
                    writeCapacityUnits = table.writeCapacityUnits,
                    builder = table.createTable,
                )
            } catch (_: ResourceInUseException) {
                log.debug { "DynamoDB table[${table.tableName}] is already being created. Waiting for readiness." }
            }
        }

        config.dynamoDbClient.waitForTableReady(table.tableName, config.tableReadyTimeout)
    }
}
