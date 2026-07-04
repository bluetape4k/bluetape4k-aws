package io.bluetape4k.aws.examples.ktor.servicecoverage

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.aws.ktor.cloudwatch.CloudWatchKtorOperations
import io.bluetape4k.aws.ktor.cloudwatch.CloudWatchLogStream
import io.bluetape4k.aws.ktor.cloudwatch.CloudWatchLogsKtorOperations
import io.bluetape4k.aws.ktor.kinesis.KinesisKtorOperations
import io.bluetape4k.aws.ktor.kinesis.KinesisPutRecordRequest
import io.bluetape4k.aws.ktor.ses.SesEmailRequest
import io.bluetape4k.aws.ktor.ses.SesKtorOperations
import io.bluetape4k.aws.ktor.sns.SnsKtorOperations
import io.bluetape4k.aws.ktor.sns.SnsPublishRequest
import io.bluetape4k.aws.ktor.sts.StsKtorOperations
import io.bluetape4k.ktor.testing.shouldHaveStatus
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.jackson.jackson
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsResponse
import software.amazon.awssdk.services.kinesis.model.PutRecordResponse
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse
import software.amazon.awssdk.services.sns.model.PublishResponse
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ServiceCoverageExampleRoutesTest {

    private val sesOperations = mockk<SesKtorOperations>()
    private val snsOperations = mockk<SnsKtorOperations>()
    private val cloudWatchOperations = mockk<CloudWatchKtorOperations>()
    private val cloudWatchLogsOperations = mockk<CloudWatchLogsKtorOperations>()
    private val kinesisOperations = mockk<KinesisKtorOperations>()
    private val stsOperations = mockk<StsKtorOperations>()

    @Test
    fun `email route sends SES v2 email`() = testApplication {
        coEvery { sesOperations.sendEmail(any()) } returns SendEmailResponse.builder()
            .messageId("ses-message-1")
            .build()
        application { installServiceCoverageExamples() }

        val response = jsonClient().post("/coverage/email") {
            contentType(ContentType.Application.Json)
            setBody("""{"to":"dev@example.com","subject":"Coverage","text":"hello"}""")
        }

        response shouldHaveStatus HttpStatusCode.OK
        response.body<SendEmailExampleResponse>().messageId shouldBeEqualTo "ses-message-1"
        coVerify(exactly = 1) {
            sesOperations.sendEmail(match<SesEmailRequest> { request ->
                request.destination.to == listOf("dev@example.com") &&
                    request.subject == "Coverage" &&
                    request.body.text == "hello"
            })
        }
    }

    @Test
    fun `notification route publishes SNS message`() = testApplication {
        coEvery { snsOperations.publish(any()) } returns PublishResponse.builder()
            .messageId("sns-message-1")
            .build()
        application { installServiceCoverageExamples() }

        val response = jsonClient().post("/coverage/notifications") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"build finished","subject":"Coverage"}""")
        }

        response shouldHaveStatus HttpStatusCode.OK
        response.body<PublishNotificationExampleResponse>().messageId shouldBeEqualTo "sns-message-1"
        coVerify(exactly = 1) {
            snsOperations.publish(match<SnsPublishRequest> { request ->
                request.topicArn == TOPIC_ARN &&
                    request.message == "build finished" &&
                    request.subject == "Coverage"
            })
        }
    }

    @Test
    fun `metric route publishes CloudWatch metric`() = testApplication {
        coEvery { cloudWatchOperations.putMetricData(NAMESPACE, any()) } returns listOf(
            PutMetricDataResponse.builder().build(),
        )
        application { installServiceCoverageExamples() }

        val response = jsonClient().post("/coverage/metrics") {
            contentType(ContentType.Application.Json)
            setBody("""{"metricName":"ProcessedJobs","value":3.0}""")
        }

        response shouldHaveStatus HttpStatusCode.OK
        response.body<MetricExampleResponse>() shouldBeEqualTo MetricExampleResponse(NAMESPACE, 1)
        coVerify(exactly = 1) {
            cloudWatchOperations.putMetricData(
                NAMESPACE,
                match<List<MetricDatum>> { metrics ->
                    metrics.single().metricName() == "ProcessedJobs" &&
                        metrics.single().value() == 3.0
                },
            )
        }
    }

    @Test
    fun `logs route writes CloudWatch Logs event`() = testApplication {
        coEvery { cloudWatchLogsOperations.putLogEvents(LOG_STREAM, any()) } returns listOf(
            PutLogEventsResponse.builder().build(),
        )
        application { installServiceCoverageExamples() }

        val response = jsonClient().post("/coverage/logs") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"example event"}""")
        }

        response shouldHaveStatus HttpStatusCode.OK
        response.body<LogEventExampleResponse>() shouldBeEqualTo
            LogEventExampleResponse(LOG_STREAM.logGroupName, LOG_STREAM.logStreamName, 1)
        coVerify(exactly = 1) {
            cloudWatchLogsOperations.putLogEvents(
                LOG_STREAM,
                match<List<InputLogEvent>> { events -> events.single().message() == "example event" },
            )
        }
    }

    @Test
    fun `stream route writes Kinesis record`() = testApplication {
        coEvery { kinesisOperations.putRecord(any()) } returns PutRecordResponse.builder()
            .sequenceNumber("sequence-1")
            .shardId("shard-000")
            .build()
        application { installServiceCoverageExamples() }

        val response = jsonClient().post("/coverage/stream-records") {
            contentType(ContentType.Application.Json)
            setBody("""{"partitionKey":"partition-a","data":"payload"}""")
        }

        response shouldHaveStatus HttpStatusCode.OK
        response.body<StreamRecordExampleResponse>() shouldBeEqualTo
            StreamRecordExampleResponse("sequence-1", "shard-000")
        coVerify(exactly = 1) {
            kinesisOperations.putRecord(match<KinesisPutRecordRequest> { request ->
                request.streamName == STREAM_NAME &&
                    request.partitionKey == "partition-a" &&
                    request.data.asUtf8String() == "payload"
            })
        }
    }

    @Test
    fun `identity route returns STS caller identity`() = testApplication {
        coEvery { stsOperations.callerIdentity() } returns GetCallerIdentityResponse.builder()
            .account("123456789012")
            .arn("arn:aws:sts::123456789012:assumed-role/example/session")
            .userId("user-1")
            .build()
        application { installServiceCoverageExamples() }

        val response = jsonClient().get("/coverage/identity")

        response shouldHaveStatus HttpStatusCode.OK
        response.body<CallerIdentityExampleResponse>() shouldBeEqualTo CallerIdentityExampleResponse(
            account = "123456789012",
            arn = "arn:aws:sts::123456789012:assumed-role/example/session",
            userId = "user-1",
        )
        coVerify(exactly = 1) { stsOperations.callerIdentity() }
    }

    private fun io.ktor.server.application.Application.installServiceCoverageExamples() {
        serviceCoverageExampleModule(
            sesOperations = sesOperations,
            snsOperations = snsOperations,
            cloudWatchOperations = cloudWatchOperations,
            cloudWatchLogsOperations = cloudWatchLogsOperations,
            kinesisOperations = kinesisOperations,
            stsOperations = stsOperations,
            options = ServiceCoverageExampleOptions(
                namespace = NAMESPACE,
                logStream = LOG_STREAM,
                kinesisStreamName = STREAM_NAME,
                snsTopicArn = TOPIC_ARN,
            ),
        )
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.jsonClient() =
        createClient {
            install(ContentNegotiation) { jackson() }
        }

    private companion object {
        private const val NAMESPACE = "Bluetape4k/AwsKtorExamples"
        private const val STREAM_NAME = "service-coverage-events"
        private const val TOPIC_ARN = "arn:aws:sns:us-east-1:000000000000:service-coverage"
        private val LOG_STREAM = CloudWatchLogStream("bluetape4k-aws-ktor-examples", "service-coverage")
    }
}
