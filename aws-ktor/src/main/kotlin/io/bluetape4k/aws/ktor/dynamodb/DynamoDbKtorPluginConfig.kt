package io.bluetape4k.aws.ktor.dynamodb

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeDefinition
import aws.sdk.kotlin.services.dynamodb.model.CreateTableRequest
import aws.sdk.kotlin.services.dynamodb.model.KeySchemaElement
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.kotlin.dynamodb.dynamoDbClientOf
import io.bluetape4k.aws.kotlin.http.HttpClientEngineProvider
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Ktor DynamoDB 플러그인 구성입니다.
 *
 * 계약:
 * - 주입된 [dynamoDbClient] 인스턴스는 애플리케이션이 계속 소유하며 플러그인은 닫지 않습니다.
 * - 클라이언트를 주입하지 않으면 [region]이 필요하고 플러그인이 AWS Kotlin SDK [DynamoDbClient]를 생성합니다.
 * - [autoCreateTables]는 명시적으로 등록한 [table] 정의만 생성합니다.
 */
class DynamoDbKtorPluginConfig {

    /** 애플리케이션이 소유하는 선택적인 AWS Kotlin SDK DynamoDB 클라이언트입니다. */
    var dynamoDbClient: DynamoDbClient? = null

    /** 테스트에서 주로 LocalStack을 지정하는 선택적인 DynamoDB 엔드포인트 재정의입니다. */
    var endpointUrl: Url? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 AWS 리전입니다. */
    var region: String? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 자격 증명 공급자입니다. */
    var credentialsProvider: CredentialsProvider? = null

    /** 플러그인이 클라이언트를 생성할 때 사용하는 선택적인 Smithy HTTP 엔진입니다. */
    var httpClient: HttpClientEngine? = null

    /** 애플리케이션 시작 시 등록된 테이블이 없으면 생성합니다. */
    var autoCreateTables: Boolean = false

    /** 새로 생성한 테이블이 CREATING 상태를 벗어날 때까지 기다릴 최대 시간입니다. */
    var tableReadyTimeout: Duration = 60.seconds

    /** 플러그인이 소유한 클라이언트를 닫을 때 기다릴 최대 시간입니다. */
    var closeTimeout: Duration = 10.seconds

    private val tableDefinitions = mutableListOf<DynamoDbKtorTableDefinition>()
    private var clientBuilder: DynamoDbClient.Config.Builder.() -> Unit = {}

    /**
     * 플러그인이 생성한 클라이언트에 추가 AWS Kotlin SDK 클라이언트 구성을 적용합니다.
     */
    fun client(builder: DynamoDbClient.Config.Builder.() -> Unit) {
        clientBuilder = builder
    }

    /**
     * 선택적인 자동 생성에 사용할 명시적 테이블 정의를 등록합니다.
     */
    fun table(
        tableName: String,
        keySchema: List<KeySchemaElement>,
        attributeDefinitions: List<AttributeDefinition>,
        readCapacityUnits: Long? = null,
        writeCapacityUnits: Long? = null,
        createTable: CreateTableRequest.Builder.() -> Unit = {},
    ) {
        tableDefinitions += DynamoDbKtorTableDefinition(
            tableName = tableName,
            keySchema = keySchema,
            attributeDefinitions = attributeDefinitions,
            readCapacityUnits = readCapacityUnits,
            writeCapacityUnits = writeCapacityUnits,
            createTable = createTable,
        )
    }

    internal fun toRuntimeConfig(defaults: AwsKtorDefaults = AwsKtorDefaults()): DynamoDbKtorRuntimeConfig {
        require(tableReadyTimeout.isPositive()) { "tableReadyTimeout must be positive." }
        require(closeTimeout.isPositive()) { "closeTimeout must be positive." }

        val injectedClient = dynamoDbClient
        val effectiveEndpointUrl = endpointUrl ?: defaults.endpointOverride?.let { Url.parse(it.toString()) }
        val effectiveRegion = region?.takeIf { it.isNotBlank() } ?: defaults.region?.takeIf { it.isNotBlank() }
        val client = injectedClient ?: dynamoDbClientOf(
            endpointUrl = effectiveEndpointUrl,
            region = requireNotNull(effectiveRegion) {
                "region must be configured when dynamoDbClient is not provided."
            },
            credentialsProvider = credentialsProvider ?: defaults.kotlinCredentialsProvider,
            httpClient = httpClient ?: defaults.kotlinHttpClient ?: HttpClientEngineProvider.defaultHttpEngine,
            builder = {
                defaults.dynamoDbClientCustomizers.forEach { it.customize(this) }
                clientBuilder()
            },
        )

        return DynamoDbKtorRuntimeConfig(
            dynamoDbClient = client,
            ownsClient = injectedClient == null,
            autoCreateTables = autoCreateTables,
            tableDefinitions = tableDefinitions.toList(),
            tableReadyTimeout = tableReadyTimeout,
            closeTimeout = closeTimeout,
        )
    }
}

/**
 * [DynamoDbKtorRuntime]이 사용하는 명시적 DynamoDB 테이블 정의입니다.
 */
class DynamoDbKtorTableDefinition(
    val tableName: String,
    val keySchema: List<KeySchemaElement>,
    val attributeDefinitions: List<AttributeDefinition>,
    val readCapacityUnits: Long? = null,
    val writeCapacityUnits: Long? = null,
    val createTable: CreateTableRequest.Builder.() -> Unit = {},
) {
    init {
        require(tableName.isNotBlank()) { "tableName must not be blank." }
        require(keySchema.isNotEmpty()) { "keySchema must not be empty." }
        require(attributeDefinitions.isNotEmpty()) { "attributeDefinitions must not be empty." }
    }
}
