package io.bluetape4k.aws.spring.kinesis

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.test.AwsSpringBootTestEmulator
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeBlank
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import org.awaitility.kotlin.await
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequestEntry
import software.amazon.awssdk.services.kinesis.model.StreamStatus
import java.time.Duration
import java.util.UUID

class KinesisCoroutinesTemplateAwsEmulatorTest {

    companion object {
        private val awsEmulator by lazy {
            AwsSpringBootTestEmulator.get("kinesis")
        }
    }

    private fun contextRunner(): ApplicationContextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                KinesisAutoConfiguration::class.java,
            )
        )
        .withBean(AwsCredentialsProvider::class.java, { awsEmulator.getCredentialProvider() })
        .withPropertyValues(
            "bluetape4k.aws.kinesis.region=${awsEmulator.regionName}",
            "bluetape4k.aws.kinesis.endpoint-override=${awsEmulator.awsEndpoint}",
            "bluetape4k.aws.kinesis.consumer.poll-interval=100ms",
            "bluetape4k.aws.kinesis.consumer.empty-backoff=100ms",
        )

    @Test
    fun `create put and collect records through KinesisOperations`() {
        contextRunner().run { context ->
            val operations = context.getBean(KinesisOperations::class.java)
            val streamName = "spring-${UUID.randomUUID()}"

            runSuspendIO {
                var streamCreated = false
                try {
                    operations.createStream(streamName, shardCount = 1)
                    streamCreated = true
                    waitUntilActive(operations, streamName)

                    val entries = (1..3).map { i ->
                        PutRecordsRequestEntry.builder()
                            .partitionKey("order-$i")
                            .data(SdkBytes.fromUtf8String("payload-$i"))
                            .build()
                    }
                    operations.putRecords(streamName, entries).failedRecordCount() shouldBeEqualTo 0

                    val shardId = operations.describeStream(streamName)
                        .streamDescription()
                        .shards()
                        .first()
                        .shardId()
                    shardId.shouldNotBeBlank()

                    val records = withTimeout(15_000) {
                        operations.recordFlow(
                            KinesisRecordFlowRequest(
                                streamName = streamName,
                                shardId = shardId,
                                position = KinesisStartingPosition.TrimHorizon,
                                options = KinesisRecordFlowOptions(
                                    batchLimit = 10,
                                    pollInterval = Duration.ofMillis(100),
                                    emptyBackoff = Duration.ofMillis(100),
                                ),
                            )
                        ).take(3).toList()
                    }

                    records shouldHaveSize 3
                    records.map { it.data().asUtf8String() } shouldBeEqualTo
                            listOf("payload-1", "payload-2", "payload-3")
                } finally {
                    if (streamCreated) {
                        operations.deleteStream(streamName)
                    }
                }
            }
        }
    }

    private fun waitUntilActive(operations: KinesisOperations, streamName: String) {
        await.atMost(Duration.ofSeconds(30)).until {
            var active = false
            runSuspendIO {
                active = operations.describeStream(streamName).streamDescription().streamStatus() == StreamStatus.ACTIVE
            }
            active
        }
    }
}
