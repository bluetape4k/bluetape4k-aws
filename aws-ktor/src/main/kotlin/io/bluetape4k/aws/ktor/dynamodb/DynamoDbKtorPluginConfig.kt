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
 * Configuration for the Ktor DynamoDB plugin.
 *
 * Contract:
 * - Injected [dynamoDbClient] instances remain owned by the application and are
 *   not closed by the plugin.
 * - When no client is injected, [region] is required and the plugin creates an
 *   AWS Kotlin SDK [DynamoDbClient].
 * - [autoCreateTables] only creates explicitly registered [table] definitions.
 */
class DynamoDbKtorPluginConfig {

    /** Optional application-owned AWS Kotlin SDK DynamoDB client. */
    var dynamoDbClient: DynamoDbClient? = null

    /** Optional DynamoDB endpoint override, commonly LocalStack in tests. */
    var endpointUrl: Url? = null

    /** AWS region used when the plugin creates the client. */
    var region: String? = null

    /** Optional credentials provider used when the plugin creates the client. */
    var credentialsProvider: CredentialsProvider? = null

    /** Optional Smithy HTTP engine used when the plugin creates the client. */
    var httpClient: HttpClientEngine? = null

    /** Creates registered tables on application start when they do not exist. */
    var autoCreateTables: Boolean = false

    /** Maximum time to wait until a newly created table leaves CREATING state. */
    var tableReadyTimeout: Duration = 60.seconds

    /** Maximum time to wait while closing a plugin-owned client. */
    var closeTimeout: Duration = 10.seconds

    private val tableDefinitions = mutableListOf<DynamoDbKtorTableDefinition>()
    private var clientBuilder: DynamoDbClient.Config.Builder.() -> Unit = {}

    /**
     * Adds extra AWS Kotlin SDK client configuration for plugin-created clients.
     */
    fun client(builder: DynamoDbClient.Config.Builder.() -> Unit) {
        clientBuilder = builder
    }

    /**
     * Registers an explicit table definition for optional auto-creation.
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
 * Explicit DynamoDB table definition used by [DynamoDbKtorRuntime].
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
