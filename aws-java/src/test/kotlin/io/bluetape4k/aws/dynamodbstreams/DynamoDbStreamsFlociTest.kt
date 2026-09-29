@file:Suppress("DEPRECATION")

package io.bluetape4k.aws.dynamodbstreams

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.aws.dynamodb.AbstractDynamodbTest
import io.bluetape4k.aws.dynamodb.model.DeleteTableRequest
import io.bluetape4k.aws.dynamodb.model.PutItemRequest
import io.bluetape4k.aws.dynamodb.model.attributeValue
import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.junit5.awaitility.untilSuspending
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withPollInterval
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType
import software.amazon.awssdk.services.dynamodb.model.StreamSpecification
import software.amazon.awssdk.services.dynamodb.model.StreamViewType
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Floci-only DynamoDB Streams capability and Java async Flow contract test. */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DynamoDbStreamsFlociTest: AbstractDynamodbTest() {

    companion object: KLogging() {
        private const val RECORD_COUNT = 3
        private val TABLE_NAME = "streams-flow-${System.nanoTime()}"
    }

    private lateinit var streamArn: String

    @Test
    @Order(1)
    fun `Floci creates a DynamoDB table with Streams enabled`() = runSuspendIO {
        client.createTable(
            CreateTableRequest.builder()
                .tableName(TABLE_NAME)
                .keySchema(
                    KeySchemaElement.builder()
                        .attributeName("id")
                        .keyType(KeyType.HASH).build()
                )
                .attributeDefinitions(
                    AttributeDefinition.builder()
                        .attributeName("id")
                        .attributeType(ScalarAttributeType.S)
                        .build(),
                )
                .provisionedThroughput(
                    ProvisionedThroughput.builder()
                        .readCapacityUnits(5)
                        .writeCapacityUnits(5)
                        .build(),
                )
                .streamSpecification(
                    StreamSpecification.builder()
                        .streamEnabled(true)
                        .streamViewType(StreamViewType.NEW_AND_OLD_IMAGES)
                        .build(),
                )
                .build(),
        )
        val describeReq = DescribeTableRequest.builder().tableName(TABLE_NAME).build()

        await atMost 30.seconds withPollInterval 100.milliseconds untilSuspending {
            client
                .describeTable(describeReq)
                .table()
                .tableStatus()
                .name == "ACTIVE"
        }

        await atMost 30.seconds withPollInterval 100.milliseconds untilSuspending {
            client
                .describeTable(describeReq)
                .table()
                .latestStreamArn() != null
        }
        streamArn = client
            .describeTable(describeReq)
            .table()
            .latestStreamArn()
            .shouldNotBeNull()

        log.debug { "streamArn: $streamArn" }
    }

    @Test
    @Order(2)
    fun `Floci records are consumed through the Java async Flow`() = runSuspendIO {
        repeat(RECORD_COUNT) { index ->
            client.putItem(
                PutItemRequest {
                    tableName(TABLE_NAME)
                    item(
                        mapOf(
                            "id" to attributeValue { s("item-$index") },
                            "value" to attributeValue { s("value-$index") },
                        )
                    )
                }
            )
        }

        withDynamoDbStreamsAsyncClient(
            endpoint = localStackServer.endpoint,
            region = localStackServer.region(),
            credentialsProvider = localStackServer.credentialsProvider,
            httpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
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
            records.all { it.streamArn == streamArn }.shouldBeTrue()
            records.map { it.shardId }.distinct().size shouldBeEqualTo 1
            checkpointStore.load(streamArn, records.first().shardId).shouldNotBeNull()
        }
    }

    @Test
    @Order(3)
    fun `Floci table cleanup completes`() = runSuspendIO {
        val response = client.deleteTable(
            DeleteTableRequest {
                tableName(TABLE_NAME)
            }
        )
        log.debug { "delete response=$response" }
    }
}
