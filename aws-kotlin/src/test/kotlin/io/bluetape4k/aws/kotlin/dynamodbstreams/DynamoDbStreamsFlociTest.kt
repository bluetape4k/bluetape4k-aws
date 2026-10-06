@file:Suppress("DEPRECATION")

package io.bluetape4k.aws.kotlin.dynamodbstreams

import aws.sdk.kotlin.services.dynamodb.describeTable
import aws.sdk.kotlin.services.dynamodb.model.KeySchemaElement
import aws.sdk.kotlin.services.dynamodb.model.KeyType
import aws.sdk.kotlin.services.dynamodb.model.ScalarAttributeType
import aws.sdk.kotlin.services.dynamodb.model.StreamSpecification
import aws.sdk.kotlin.services.dynamodb.model.StreamViewType
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.aws.kotlin.dynamodb.AbstractKotlinDynamoDbTest
import io.bluetape4k.aws.kotlin.dynamodb.createTable
import io.bluetape4k.aws.kotlin.dynamodb.deleteTableIfExists
import io.bluetape4k.aws.kotlin.dynamodb.model.attributeDefinitionOf
import io.bluetape4k.aws.kotlin.dynamodb.putItem
import io.bluetape4k.aws.kotlin.dynamodb.waitForTableReady
import io.bluetape4k.coroutines.flow.extensions.log
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.testcontainers.utility.Base58
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Floci-only DynamoDB Streams capability and Flow contract test. */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DynamoDbStreamsFlociTest: AbstractKotlinDynamoDbTest() {

    companion object: KLogging() {
        private const val RECORD_COUNT = 3
        private val TABLE_NAME = "streams-flow-${Base58.randomString(8).lowercase()}"
    }

    private lateinit var streamArn: String

    @Test
    @Order(1)
    fun `Floci creates a DynamoDB table with Streams enabled`() = runSuspendIO {
        withLocalDynamoDbClient { client ->
            client.deleteTableIfExists(TABLE_NAME)
            val response = client.createTable(TABLE_NAME) {
                keySchema = listOf(KeySchemaElement { attributeName = "id"; keyType = KeyType.Hash })
                attributeDefinitions = listOf(attributeDefinitionOf("id", ScalarAttributeType.S))
                provisionedThroughput {
                    readCapacityUnits = 5
                    writeCapacityUnits = 5
                }
                streamSpecification = StreamSpecification {
                    streamEnabled = true
                    streamViewType = StreamViewType.NewAndOldImages
                }
            }
            log.debug { "Created DynamoDB table. response=$response" }
            response.tableDescription?.tableName shouldBeEqualTo TABLE_NAME

            client.waitForTableReady(TABLE_NAME)

            streamArn = withTimeout(30.seconds) {
                var arn: String? = null
                while (arn == null) {
                    arn = client.describeTable { tableName = TABLE_NAME }.table?.latestStreamArn
                    if (arn == null) delay(100.milliseconds)
                }
                arn.shouldNotBeNull()
            }
            streamArn.shouldNotBeNull()
        }
    }

    @Test
    @Order(2)
    fun `Floci records are consumed through the Kotlin Flow`() = runSuspendIO {
        withLocalDynamoDbClient { client ->
            repeat(RECORD_COUNT) { index ->
                val response = client.putItem(
                    TABLE_NAME,
                    mapOf("id" to "item-$index", "value" to "value-$index")
                )
                log.debug { "Put item response=$response" }
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
                ).log("RECORDS")
                    .take(RECORD_COUNT).toList()
            }

            records.size shouldBeEqualTo RECORD_COUNT
            records.all { it.streamArn == streamArn }.shouldBeTrue()
            records.map { it.shardId }.distinct().size shouldBeEqualTo 1
            checkpointStore.load(streamArn, records.first().shardId).shouldNotBeNull()
        }
    }

    @Test
    @Order(3)
    fun `Floci table cleanup completes`() = runSuspendIO {
        withLocalDynamoDbClient { client ->
            val response = client.deleteTableIfExists(TABLE_NAME)
            log.debug { "response=$response" }
            response.shouldNotBeNull().tableDescription?.tableName shouldBeEqualTo TABLE_NAME
        }
    }
}
