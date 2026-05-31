@file:Suppress("DEPRECATION")

package io.bluetape4k.aws.ktor.dynamodb

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.createTable
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.BillingMode
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.aws.kotlin.dynamodb.DynamoItemMapper
import io.bluetape4k.aws.kotlin.dynamodb.DynamoItemReader
import io.bluetape4k.aws.kotlin.dynamodb.deleteTableIfExists
import io.bluetape4k.aws.kotlin.dynamodb.dynamoDbClientOf
import io.bluetape4k.aws.kotlin.dynamodb.existsTable
import io.bluetape4k.aws.kotlin.dynamodb.model.partitionKeyOf
import io.bluetape4k.aws.kotlin.dynamodb.model.stringAttrDefinitionOf
import io.bluetape4k.aws.kotlin.dynamodb.waitForTableReady
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.testcontainers.aws.AwsEmulatorServer
import io.bluetape4k.testcontainers.aws.FlociServer
import io.bluetape4k.testcontainers.aws.LocalStackServer
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.Serializable
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamoDbKtorRuntimeLocalStackTest {

    private val awsEmulator: AwsEmulatorServer by lazy { awsEmulator("dynamodb") }

    private val client: DynamoDbClient by lazy {
        dynamoDbClientOf(
            endpointUrl = Url.parse(awsEmulator.awsEndpoint.toString()),
            region = awsEmulator.regionName,
            credentialsProvider = StaticCredentialsProvider {
                accessKeyId = awsEmulator.awsAccessKey
                secretAccessKey = awsEmulator.awsSecretKey
            },
        )
    }

    @Test
    fun `runtime skips auto create when disabled`() = runSuspendIO {
        val tableName = tableName("disabled")

        try {
            val runtime = DynamoDbKtorRuntime(
                DynamoDbKtorRuntimeConfig(
                    dynamoDbClient = client,
                    ownsClient = false,
                    autoCreateTables = false,
                    tableDefinitions = listOf(tableDefinition(tableName)),
                )
            )

            runtime.start()

            client.existsTable(tableName).shouldBeFalse()
        } finally {
            client.deleteTableIfExists(tableName)
        }
    }

    @Test
    fun `runtime skips existing tables during auto create`() = runSuspendIO {
        val tableName = tableName("existing")

        try {
            createPayPerRequestTable(tableName)

            val runtime = DynamoDbKtorRuntime(
                DynamoDbKtorRuntimeConfig(
                    dynamoDbClient = client,
                    ownsClient = false,
                    autoCreateTables = true,
                    tableDefinitions = listOf(tableDefinition(tableName)),
                )
            )

            runtime.start()

            client.existsTable(tableName).shouldBeTrue()
        } finally {
            client.deleteTableIfExists(tableName)
        }
    }

    @Test
    fun `Ktor plugin auto creates table and repository stores reads scans and queries items`() = testApplication {
        val tableName = tableName("plugin")
        val dynamoDb = this@DynamoDbKtorRuntimeLocalStackTest.client

        application {
            install(DynamoDbKtorPlugin) {
                dynamoDbClient = dynamoDb
                autoCreateTables = true
                table(
                    tableName = tableName,
                    keySchema = listOf(partitionKeyOf("id")),
                    attributeDefinitions = listOf(stringAttrDefinitionOf("id")),
                ) {
                    billingMode = BillingMode.PayPerRequest
                }
            }
        }

        try {
            startApplication()
            dynamoDb.existsTable(tableName).shouldBeTrue()

            val repository = application.dynamoDb().repository(
                tableName = tableName,
                mapper = itemMapper,
                reader = itemReader,
                keyMapper = keyMapper,
            )
            val item = TestRecord(id = "item-1", name = "Ktor", score = 42)

            repository.save(item)

            repository.findById(item.id) shouldBeEqualTo item

            val scanned = repository.scan().toList()
            scanned shouldHaveSize 1
            scanned.single() shouldBeEqualTo item

            val queried = repository.query {
                keyConditionExpression = "#id = :id"
                expressionAttributeNames = mapOf("#id" to "id")
                expressionAttributeValues = mapOf(":id" to AttributeValue.S(item.id))
            }.toList()
            queried shouldHaveSize 1
            queried.single() shouldBeEqualTo item

            repository.deleteById(item.id)
            repository.findById(item.id).shouldBeNull()
        } finally {
            dynamoDb.deleteTableIfExists(tableName)
        }
    }

    private suspend fun createPayPerRequestTable(tableName: String) {
        client.createTable {
            this.tableName = tableName
            keySchema = listOf(partitionKeyOf("id"))
            attributeDefinitions = listOf(stringAttrDefinitionOf("id"))
            billingMode = BillingMode.PayPerRequest
        }
        client.waitForTableReady(tableName)
    }

    private fun tableDefinition(tableName: String): DynamoDbKtorTableDefinition =
        DynamoDbKtorTableDefinition(
            tableName = tableName,
            keySchema = listOf(partitionKeyOf("id")),
            attributeDefinitions = listOf(stringAttrDefinitionOf("id")),
            createTable = {
                billingMode = BillingMode.PayPerRequest
            },
        )

    private fun tableName(prefix: String): String =
        "ktor-dynamodb-$prefix-${UUID.randomUUID()}"

    data class TestRecord(
        val id: String,
        val name: String,
        val score: Int,
    ): Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    private val itemMapper = DynamoItemMapper<TestRecord> { item ->
        mapOf(
            "id" to AttributeValue.S(item.id),
            "name" to AttributeValue.S(item.name),
            "score" to AttributeValue.N(item.score.toString()),
        )
    }

    private val itemReader = DynamoItemReader<TestRecord> { item ->
        TestRecord(
            id = item.getValue("id").asS(),
            name = item.getValue("name").asS(),
            score = item.getValue("score").asN().toInt(),
        )
    }

    private val keyMapper = DynamoItemMapper<String> { id ->
        mapOf("id" to AttributeValue.S(id))
    }

    private fun awsEmulator(vararg services: String): AwsEmulatorServer =
        when (val emulator = System.getProperty("bluetape4k.aws.emulator", "floci").trim().lowercase()) {
            "floci" -> FlociServer.Launcher.floci
            "localstack" -> LocalStackServer.Launcher.getLocalStack(*services)
            else -> error("Unsupported AWS emulator: $emulator. Use floci or localstack.")
        }
}
