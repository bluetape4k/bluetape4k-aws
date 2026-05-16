package io.bluetape4k.aws.examples.ktor.sqs

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.aws.ktor.sqs.SqsConsumer
import io.bluetape4k.aws.ktor.sqs.SqsConsumerRuntimeConfig
import io.bluetape4k.aws.ktor.sqs.SqsConsumerRuntime
import io.bluetape4k.aws.ktor.sqs.sqsConsumer
import io.bluetape4k.aws.sqs.SqsClientFactory
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.testcontainers.aws.LocalStackServer
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.future.await
import org.awaitility.kotlin.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqsExampleRoutesLocalStackTest {

    companion object {
        @Suppress("DEPRECATION")
        private val localStack: LocalStackServer by lazy {
            LocalStackServer.Launcher.getLocalStack("sqs")
        }

        private val sqsClient: SqsAsyncClient by lazy {
            SqsClientFactory.Async.create(
                endpointOverride = localStack.awsEndpoint,
                region = Region.of(localStack.regionName),
                credentialsProvider = localStack.getCredentialProvider(),
            )
        }
    }

    private lateinit var queueUrl: String

    @BeforeAll
    fun setUp() = runSuspendIO {
        val name = "ktor-sqs-example-${UUID.randomUUID()}"
        queueUrl = sqsClient.createQueue { it.queueName(name) }.await().queueUrl()
    }

    @AfterAll
    fun tearDown() = runSuspendIO {
        sqsClient.deleteQueue { it.queueUrl(queueUrl) }.await()
    }

    @Test
    fun `consumer receives message sent directly via runtime`() = runSuspendIO {
        // Use a fresh queue so other testApplication tests do not consume our message
        val testQueueUrl = sqsClient.createQueue {
            it.queueName("ktor-sqs-direct-${UUID.randomUUID()}")
        }.await().queueUrl()

        val received = ConcurrentHashMap.newKeySet<String>()
        val runtime = SqsConsumerRuntime(
            SqsConsumerRuntimeConfig(
                sqsAsyncClient = sqsClient,
                queueUrl = testQueueUrl,
                coroutines = 2,
                maxMessages = 10,
                waitTimeSeconds = 1,
                visibilityTimeoutSeconds = 5,
                messageType = String::class,
                messageHandler = { body -> received += body as String },
            )
        )
        try {
            runtime.start()
            val body = "hello-${UUID.randomUUID()}"
            runtime.send(body, testQueueUrl)

            await.atMost(Duration.ofSeconds(30)).untilAsserted {
                received.contains(body).shouldBeTrue()
            }
        } finally {
            runtime.stop()
            sqsClient.deleteQueue { it.queueUrl(testQueueUrl) }.await()
        }
    }

    @Test
    fun `send route returns messageId`() = testApplication {
        application { sqsExampleModule(sqsClient, queueUrl) }

        val body = "hello-${UUID.randomUUID()}"
        val sendResponse = client.post("/sqs/messages") {
            contentType(ContentType.Text.Plain)
            setBody(body)
        }
        sendResponse.status shouldBeEqualTo HttpStatusCode.OK
        val responseText = sendResponse.bodyAsText()
        assert(responseText.contains("messageId")) {
            "Expected messageId in response but got: $responseText"
        }
    }

    @Test
    fun `get queue attributes returns message count`() = testApplication {
        application { sqsExampleModule(sqsClient, queueUrl) }

        val response = client.get("/sqs/queues/attributes?url=$queueUrl")
        response.status shouldBeEqualTo HttpStatusCode.OK
        val responseBody = response.bodyAsText()
        assert(responseBody.contains("approximateMessageCount")) {
            "Expected approximateMessageCount in response but got: $responseBody"
        }
    }

    @Test
    fun `create queue route creates a new queue`() = testApplication {
        application { sqsExampleModule(sqsClient, queueUrl) }

        val queueName = "created-${UUID.randomUUID()}"
        val response = client.post("/sqs/queues/$queueName")
        response.status shouldBeEqualTo HttpStatusCode.OK
        val responseBody = response.bodyAsText()
        assert(responseBody.contains("queueUrl")) {
            "Expected queueUrl in response but got: $responseBody"
        }
    }
}
