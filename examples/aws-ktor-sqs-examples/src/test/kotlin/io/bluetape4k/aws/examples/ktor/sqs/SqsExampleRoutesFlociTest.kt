package io.bluetape4k.aws.examples.ktor.sqs

import aws.sdk.kotlin.services.sqs.SqsClient
import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.aws.kotlin.sqs.sqsClientOf
import io.bluetape4k.aws.kotlin.sqs.createQueue
import io.bluetape4k.aws.kotlin.sqs.deleteQueue
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.ktor.testing.shouldHaveStatus
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.testcontainers.aws.FlociServer
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class SqsExampleRoutesFlociTest {

    companion object: KLoggingChannel() {
        val floci: FlociServer by lazy { FlociServer.Launcher.floci }

        val sqsClient: SqsClient by lazy {
            sqsClientOf(
                endpointUrl = Url.parse(floci.awsEndpoint.toString()),
                region = floci.regionName,
                credentialsProvider = StaticCredentialsProvider {
                    accessKeyId = floci.awsAccessKey
                    secretAccessKey = floci.awsSecretKey
                },
            )
        }
    }

    private lateinit var queueUrl: String

    @BeforeAll
    fun setUp() = runSuspendIO {
        queueUrl = requireNotNull(
            sqsClient.createQueue("ktor-sqs-example-${Base58.randomString(8)}").queueUrl,
        )
    }

    @AfterAll
    fun tearDown() = runSuspendIO {
        val queueUrlToDelete = queueUrl
        sqsClient.deleteQueue(queueUrlToDelete)
        sqsClient.close()
    }

    @Test
    fun `send route returns messageId`() = testApplication {
        application { sqsExampleModule(sqsClient, queueUrl) }

        val response = client.post("/sqs/messages") {
            contentType(ContentType.Text.Plain)
            setBody("hello-${Base58.randomString(8)}")
        }
        response shouldHaveStatus HttpStatusCode.OK
        response.bodyAsText() shouldContain "messageId"
    }

    @Test
    fun `get queue attributes returns approximateMessageCount`() = testApplication {
        application { sqsExampleModule(sqsClient, queueUrl) }

        val response = client.get("/sqs/queues/attributes?url=$queueUrl")
        response shouldHaveStatus HttpStatusCode.OK
        response.bodyAsText() shouldContain "approximateMessageCount"
    }

    @Test
    fun `create queue route creates a new queue`() = testApplication {
        application { sqsExampleModule(sqsClient, queueUrl) }

        val queueName = "ktor-sqs-created-${Base58.randomString(8)}"
        val response = client.post("/sqs/queues/$queueName")
        response shouldHaveStatus HttpStatusCode.OK
        response.bodyAsText() shouldContain "queueUrl"
    }

    @Test
    fun `concurrent sends via route all succeed`() = testApplication {
        application { sqsExampleModule(sqsClient, queueUrl) }

        SuspendedJobTester()
            .workers(4)
            .rounds(5)
            .add {
                client.post("/sqs/messages") {
                    contentType(ContentType.Text.Plain)
                    setBody("concurrent-${Base58.randomString(8)}")
                } shouldHaveStatus HttpStatusCode.OK
            }
            .run()
    }

    @Test
    fun `advanced consumer routes expose manual ack retry interceptor and observer events`() = testApplication {
        val advancedQueueUrl = sqsClient.createQueue("ktor-sqs-advanced-${Base58.randomString(8)}").queueUrl.orEmpty()

        application { sqsExampleModule(sqsClient, advancedQueueUrl) }

        try {
            val normalBody = "manual-ack:${Base58.randomString(8)}"
            val retryBody = "retry-once:${Base58.randomString(8)}"

            client.post("/sqs/messages") {
                contentType(ContentType.Text.Plain)
                setBody(normalBody)
            } shouldHaveStatus HttpStatusCode.OK

            client.post("/sqs/messages") {
                contentType(ContentType.Text.Plain)
                setBody(retryBody)
            } shouldHaveStatus HttpStatusCode.OK

            withTimeout(15.seconds) {
                while (true) {
                    val received = client.get("/sqs/messages/received").bodyAsText()
                    val lifecycleEvents = client.get("/sqs/messages/lifecycle-events").bodyAsText()
                    val observations = client.get("/sqs/messages/observations").bodyAsText()
                    if (
                        received.contains(normalBody) &&
                        lifecycleEvents.contains("afterNack") &&
                        lifecycleEvents.contains("afterAck") &&
                        observations.contains("\"operation\":\"nack\"") &&
                        observations.contains("\"operation\":\"ack\"")
                    ) {
                        break
                    }
                    delay(100.milliseconds)
                }
            }
        } finally {
            sqsClient.deleteQueue(advancedQueueUrl)
        }
    }
}
