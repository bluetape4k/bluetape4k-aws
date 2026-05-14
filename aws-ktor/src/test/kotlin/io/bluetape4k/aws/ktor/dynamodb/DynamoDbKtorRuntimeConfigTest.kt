package io.bluetape4k.aws.ktor.dynamodb

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeDefinition
import aws.sdk.kotlin.services.dynamodb.model.KeySchemaElement
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.aws.kotlin.dynamodb.model.partitionKeyOf
import io.bluetape4k.aws.kotlin.dynamodb.model.stringAttrDefinitionOf
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamoDbKtorRuntimeConfigTest {

    @Test
    fun `requires region when client is not injected`() {
        assertFailsWith<IllegalArgumentException> {
            DynamoDbKtorPluginConfig().toRuntimeConfig()
        }
    }

    @Test
    fun `validates table definition`() {
        assertFailsWith<IllegalArgumentException> {
            DynamoDbKtorTableDefinition(
                tableName = " ",
                keySchema = keySchema,
                attributeDefinitions = attributeDefinitions,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            DynamoDbKtorTableDefinition(
                tableName = "orders",
                keySchema = emptyList(),
                attributeDefinitions = attributeDefinitions,
            )
        }
    }

    @Test
    fun `does not close injected client`() = runSuspendIO {
        val client = mockk<DynamoDbClient>(relaxed = true)
        every { client.close() } returns Unit

        val runtime = DynamoDbKtorRuntime(
            DynamoDbKtorRuntimeConfig(
                dynamoDbClient = client,
                ownsClient = false,
                closeTimeout = 1.seconds,
            )
        )

        runtime.stop()

        verify(exactly = 0) { client.close() }
    }

    @Test
    fun `closes plugin owned client`() = runSuspendIO {
        val client = mockk<DynamoDbClient>(relaxed = true)
        every { client.close() } returns Unit

        val runtime = DynamoDbKtorRuntime(
            DynamoDbKtorRuntimeConfig(
                dynamoDbClient = client,
                ownsClient = true,
                closeTimeout = 1.seconds,
            )
        )

        runtime.stop()

        verify(exactly = 1) { client.close() }
    }

    private val keySchema: List<KeySchemaElement> = listOf(partitionKeyOf("id"))
    private val attributeDefinitions: List<AttributeDefinition> = listOf(stringAttrDefinitionOf("id"))
}
