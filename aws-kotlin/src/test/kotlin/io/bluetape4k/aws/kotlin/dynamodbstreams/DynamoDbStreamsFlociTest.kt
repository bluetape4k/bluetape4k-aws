@file:Suppress("DEPRECATION")

package io.bluetape4k.aws.kotlin.dynamodbstreams

import aws.sdk.kotlin.services.dynamodb.model.AttributeDefinition
import aws.sdk.kotlin.services.dynamodb.model.KeySchemaElement
import aws.sdk.kotlin.services.dynamodb.model.KeyType
import aws.sdk.kotlin.services.dynamodb.model.ScalarAttributeType
import aws.sdk.kotlin.services.dynamodb.model.StreamSpecification
import aws.sdk.kotlin.services.dynamodb.model.StreamViewType
import aws.sdk.kotlin.services.dynamodb.describeTable
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.aws.kotlin.AbstractAwsTest
import io.bluetape4k.aws.kotlin.dynamodb.createTable
import io.bluetape4k.aws.kotlin.dynamodb.deleteTableIfExists
import io.bluetape4k.aws.kotlin.dynamodb.putItem
import io.bluetape4k.aws.kotlin.dynamodb.waitForTableReady
import io.bluetape4k.aws.kotlin.dynamodb.withDynamoDbClient
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Floci-only DynamoDB Streams capability and Flow contract test. */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DynamoDbStreamsFlociTest : AbstractAwsTest() {

    companion object {
        private const val RECORD_COUNT = 3
        private val TABLE_NAME = "streams-flow-${System.nanoTime()}"
    }

    private lateinit var streamArn: String

    @Test
    @Order(1)
    fun `Floci creates a DynamoDB table with Streams enabled`() = runSuspendIO {
        withDynamoDbClient(
            localStackServer.endpointUrl,
            localStackServer.region,
            localStackServer.credentialsProvider,
        ) {
            client ->
            client.deleteTableIfExists(TABLE_NAME)
            client.createTable(TABLE_NAME) {
                keySchema = listOf(KeySchemaElement { attributeName = "id"; keyType = KeyType.Hash })
                attributeDefinitions = listOf(AttributeDefinition {
                    attributeName = "id"
                    attributeType = ScalarAttributeType.S
                })
                provisionedThroughput {
                    readCapacityUnits = 5
                    writeCapacityUnits = 5
                }
                streamSpecification = StreamSpecification {
                    streamEnabled = true
                    streamViewType = StreamViewType.NewAndOldImages
                }
            }
            client.waitForTableReady(TABLE_NAME)
            streamArn = withTimeout(30.seconds) {
                var arn: String? = null
                while (arn == null) {
                    arn = client.describeTable { tableName = TABLE_NAME }.table?.latestStreamArn
                    if (arn == null) delay(100.milliseconds)
                }
                checkNotNull(arn)
            }
            streamArn.shouldNotBeNull()
        }
    }

    @Test
    @Order(2)
    fun `Floci records are consumed through the Kotlin Flow`() = runSuspendIO {
        withDynamoDbClient(
            localStackServer.endpointUrl,
            localStackServer.region,
            localStackServer.credentialsProvider,
        ) {
            client ->
            repeat(RECORD_COUNT) { index ->
                client.putItem(TABLE_NAME, mapOf("id" to "item-$index", "value" to "value-$index"))
            }
        }

        withDynamoDbStreamsClient(
            localStackServer.endpointUrl,
            localStackServer.region,
            localStackServer.credentialsProvider,
        ) { streamsClient ->
            val options = DynamoDbStreamsRecordFlowOptions(
                pollInterval = 200.milliseconds,
                emptyBackoff = 200.milliseconds,
            )
            val checkpointStore = InMemoryDynamoDbStreamsCheckpointStore()
            val records = withTimeout(45.seconds) {
                streamsClient.shardRecordFlow(
                    streamArn = streamArn,
                    options = options,
                    checkpointStore = checkpointStore,
                ).take(RECORD_COUNT).toList()
            }

            records.size shouldBeEqualTo RECORD_COUNT
            records.all { it.streamArn == streamArn } shouldBeEqualTo true
            records.map { it.shardId }.distinct().size shouldBeEqualTo 1
            checkpointStore.load(streamArn, records.first().shardId).shouldNotBeNull()
        }
    }

    @Test
    @Order(3)
    fun `Floci table cleanup completes`() = runSuspendIO {
        withDynamoDbClient(
            localStackServer.endpointUrl,
            localStackServer.region,
            localStackServer.credentialsProvider,
        ) {
            client -> client.deleteTableIfExists(TABLE_NAME)
        }
    }
}
