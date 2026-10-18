package io.bluetape4k.aws.spring.sns

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import java.util.*

/** Floci에서 SNS PublishBatch의 chunking과 ordered result를 검증합니다. */
class SnsBatchExecutionFlociTest: AbstractSnsTest() {

    companion object: KLogging()

    @Test
    fun `Floci publishes SNS batch through the guarded template`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AwsAutoConfiguration::class.java,
                    SnsAutoConfiguration::class.java,
                )
            )
            .withBean(AwsCredentialsProvider::class.java, { awsEmulator.getCredentialProvider() })
            .withPropertyValues(
                "${SnsProperties.PREFIX}.region=${awsEmulator.regionName}",
                "${SnsProperties.PREFIX}.endpoint-override=${awsEmulator.awsEndpoint}",
            )
            .run { context ->
                val sns = context.getBean<SnsOperations>()
                runSuspendIO {
                    val topicArn = sns.createTopic("batch-${UUID.randomUUID()}")
                    val entries = (1..12).map { index ->
                        SnsPublishBatchEntry(
                            id = "entry-$index",
                            message = "message-$index",
                        )
                    }

                    val result = sns.publishBatch(
                        SnsPublishBatchRequest(topicArn, entries),
                        SnsBatchExecutionOptions(maxInFlightBatches = 2),
                    )

                    log.debug { "result=$result" }
                    result.successful shouldHaveSize entries.size
                    result.failed shouldHaveSize 0
                    result.successful.map { it.entryId } shouldBeEqualTo entries.map { it.id }
                }
            }
    }
}
