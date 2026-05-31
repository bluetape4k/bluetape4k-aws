package io.bluetape4k.aws.examples.ktor.sqs

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.aws.sqs.SqsClientFactory
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.ktor.testing.shouldHaveStatus
import io.bluetape4k.testcontainers.aws.FlociServer
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class SqsExampleRoutesFlociTest {

    companion object {
        val floci: FlociServer by lazy { FlociServer.Launcher.floci }

        val sqsClient: SqsAsyncClient by lazy {
            SqsClientFactory.Async.create(
                endpointOverride = floci.awsEndpoint,
                region = Region.of(floci.regionName),
                credentialsProvider = floci.getCredentialProvider(),
            )
        }
    }

    private lateinit var queueUrl: String

    @BeforeAll
    fun setUp() = runSuspendIO {
        queueUrl = sqsClient.createQueue { it.queueName("ktor-sqs-example-${Base58.randomString(8)}") }
            .await().queueUrl()
    }

    @AfterAll
    fun tearDown() = runSuspendIO {
        sqsClient.deleteQueue { it.queueUrl(queueUrl) }.await()
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
        response.bodyAsText().contains("messageId").shouldBeTrue()
    }

    @Test
    fun `get queue attributes returns approximateMessageCount`() = testApplication {
        application { sqsExampleModule(sqsClient, queueUrl) }

        val response = client.get("/sqs/queues/attributes?url=$queueUrl")
        response shouldHaveStatus HttpStatusCode.OK
        response.bodyAsText().contains("approximateMessageCount").shouldBeTrue()
    }

    @Test
    fun `create queue route creates a new queue`() = testApplication {
        application { sqsExampleModule(sqsClient, queueUrl) }

        val queueName = "ktor-sqs-created-${Base58.randomString(8)}"
        val response = client.post("/sqs/queues/$queueName")
        response shouldHaveStatus HttpStatusCode.OK
        response.bodyAsText().contains("queueUrl").shouldBeTrue()
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
        val advancedQueueUrl = sqsClient.createQueue {
            it.queueName("ktor-sqs-advanced-${Base58.randomString(8)}")
        }.await().queueUrl()
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

            withTimeout(15_000) {
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
                    delay(100)
                }
            }
        } finally {
            sqsClient.deleteQueue { it.queueUrl(advancedQueueUrl) }.await()
        }
    }
}
