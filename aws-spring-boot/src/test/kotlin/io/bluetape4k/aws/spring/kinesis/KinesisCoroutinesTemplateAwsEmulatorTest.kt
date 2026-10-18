package io.bluetape4k.aws.spring.kinesis

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeBlank
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.aws.core.toSdkBytes
import io.bluetape4k.aws.kinesis.model.putRecordsRequestEntryOf
import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.test.AwsSpringBootTestEmulator
import io.bluetape4k.coroutines.flow.extensions.log
import io.bluetape4k.javatimes.millis
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.testcontainers.utility.Base58
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import kotlin.time.Duration.Companion.seconds

class KinesisCoroutinesTemplateAwsEmulatorTest {

    companion object: KLogging() {
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
            val operations = context.getBean<KinesisOperations>().shouldNotBeNull()
            val streamName = "spring-${Base58.randomString(12).lowercase()}"

            runSuspendIO {
                var streamCreated = false
                try {
                    operations.createStream(streamName, shardCount = 1)
                    streamCreated = true
                    waitUntilActive(operations, streamName)

                    val entries = List(3) { i ->
                        putRecordsRequestEntryOf("order-$i", "payload-$i".toSdkBytes())
                    }
                    operations.putRecords(streamName, entries).failedRecordCount() shouldBeEqualTo 0

                    val shardId = operations.describeStream(streamName)
                        .streamDescription()
                        .shards()
                        .first()
                        .shardId()
                    shardId.shouldNotBeBlank()

                    val records = withTimeout(15.seconds) {
                        operations
                            .recordFlow(
                                KinesisRecordFlowRequest(
                                    streamName = streamName,
                                    shardId = shardId,
                                    position = KinesisStartingPosition.TrimHorizon,
                                    options = KinesisRecordFlowOptions(
                                        batchLimit = 10,
                                        pollInterval = 100.millis(),
                                        emptyBackoff = 100.millis(),
                                    ),
                                )
                            )
                            .log("RECORD")
                            .take(3)
                            .toList()
                    }

                    records shouldHaveSize 3
                    records.map { it.data().asUtf8String() } shouldBeEqualTo listOf(
                        "payload-0",
                        "payload-1",
                        "payload-2"
                    )
                } finally {
                    if (streamCreated) {
                        operations.deleteStream(streamName)
                    }
                }
            }
        }
    }

    private fun waitUntilActive(operations: KinesisOperations, streamName: String) {
        log.debug("Waiting for Kinesis stream[$streamName] to be active...")
        operations.client.waiter().waitUntilStreamExists { it.streamName(streamName) }
    }
}
