package io.bluetape4k.aws.examples.ktor.sqs

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.aws.sqs.SqsClientFactory
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
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
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqsExampleRoutesLocalStackTest {

    companion object {
        @Suppress("DEPRECATION")
        val localStack: LocalStackServer by lazy {
            LocalStackServer.Launcher.getLocalStack("sqs")
        }

        val sqsClient: SqsAsyncClient by lazy {
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
        queueUrl = sqsClient.createQueue { it.queueName("ktor-sqs-example-${UUID.randomUUID()}") }
            .await().queueUrl()
    }

    @AfterAll
    fun tearDown() = runSuspendIO {
        sqsClient.deleteQueue { it.queueUrl(queueUrl) }.await()
    }

    @Test
    fun `send route returns messageId`() = testApplication {
        application { sqsExampleModule(sqsClient, queueUrl) }

        val response = client.post("/sqs/messages") {
            contentType(ContentType.Text.Plain)
            setBody("hello-${UUID.randomUUID()}")
        }
        response.status shouldBeEqualTo HttpStatusCode.OK
        response.bodyAsText().contains("messageId").shouldBeTrue()
    }

    @Test
    fun `get queue attributes returns approximateMessageCount`() = testApplication {
        application { sqsExampleModule(sqsClient, queueUrl) }

        val response = client.get("/sqs/queues/attributes?url=$queueUrl")
        response.status shouldBeEqualTo HttpStatusCode.OK
        response.bodyAsText().contains("approximateMessageCount").shouldBeTrue()
    }

    @Test
    fun `create queue route creates a new queue`() = testApplication {
        application { sqsExampleModule(sqsClient, queueUrl) }

        val queueName = "ktor-sqs-created-${UUID.randomUUID()}"
        val response = client.post("/sqs/queues/$queueName")
        response.status shouldBeEqualTo HttpStatusCode.OK
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
                    setBody("concurrent-${UUID.randomUUID()}")
                }.status shouldBeEqualTo HttpStatusCode.OK
            }
            .run()
    }
}
