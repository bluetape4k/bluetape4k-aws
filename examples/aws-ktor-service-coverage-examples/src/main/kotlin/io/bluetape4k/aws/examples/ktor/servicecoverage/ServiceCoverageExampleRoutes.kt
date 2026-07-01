package io.bluetape4k.aws.examples.ktor.servicecoverage

import io.bluetape4k.aws.ktor.cloudwatch.CloudWatchKtorOperations
import io.bluetape4k.aws.ktor.cloudwatch.CloudWatchKtorPlugin
import io.bluetape4k.aws.ktor.cloudwatch.CloudWatchLogStream
import io.bluetape4k.aws.ktor.cloudwatch.CloudWatchLogsKtorOperations
import io.bluetape4k.aws.ktor.cloudwatch.CloudWatchLogsKtorPlugin
import io.bluetape4k.aws.ktor.cloudwatch.cloudWatch
import io.bluetape4k.aws.ktor.cloudwatch.cloudWatchLogs
import io.bluetape4k.aws.ktor.kinesis.KinesisKtorOperations
import io.bluetape4k.aws.ktor.kinesis.KinesisKtorPlugin
import io.bluetape4k.aws.ktor.kinesis.KinesisKtorStream
import io.bluetape4k.aws.ktor.kinesis.KinesisPutRecordRequest
import io.bluetape4k.aws.ktor.kinesis.kinesis
import io.bluetape4k.aws.ktor.ses.SesEmailAddressSet
import io.bluetape4k.aws.ktor.ses.SesEmailBody
import io.bluetape4k.aws.ktor.ses.SesEmailRequest
import io.bluetape4k.aws.ktor.ses.SesKtorOperations
import io.bluetape4k.aws.ktor.ses.SesKtorPlugin
import io.bluetape4k.aws.ktor.ses.ses
import io.bluetape4k.aws.ktor.sns.SnsKtorOperations
import io.bluetape4k.aws.ktor.sns.SnsKtorPlugin
import io.bluetape4k.aws.ktor.sns.SnsPublishRequest
import io.bluetape4k.aws.ktor.sns.sns
import io.bluetape4k.aws.ktor.sts.StsKtorOperations
import io.bluetape4k.aws.ktor.sts.StsKtorPlugin
import io.bluetape4k.aws.ktor.sts.sts
import io.bluetape4k.support.requireNotBlank
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent
import java.io.Serializable

/**
 * Installs Ktor routes that exercise the remaining AWS service plugin coverage.
 *
 * ## Behavior / Contract
 *
 * The module installs SES/v2, SNS, CloudWatch, CloudWatch Logs, Kinesis, and
 * STS plugins with application-owned operation facades. Routes call the plugin
 * accessors, so tests and sample clients cover the same Ktor integration path
 * that production applications use.
 */
fun Application.serviceCoverageExampleModule(
    sesOperations: SesKtorOperations,
    snsOperations: SnsKtorOperations,
    cloudWatchOperations: CloudWatchKtorOperations,
    cloudWatchLogsOperations: CloudWatchLogsKtorOperations,
    kinesisOperations: KinesisKtorOperations,
    stsOperations: StsKtorOperations,
    options: ServiceCoverageExampleOptions = ServiceCoverageExampleOptions(),
) {
    install(ContentNegotiation) { jackson() }

    install(SesKtorPlugin) {
        this.sesOperations = sesOperations
    }
    install(SnsKtorPlugin) {
        this.snsOperations = snsOperations
    }
    install(CloudWatchKtorPlugin) {
        this.cloudWatchOperations = cloudWatchOperations
        namespace = options.namespace
    }
    install(CloudWatchLogsKtorPlugin) {
        this.cloudWatchLogsOperations = cloudWatchLogsOperations
        logGroupName = options.logStream.logGroupName
        logStreamName = options.logStream.logStreamName
    }
    install(KinesisKtorPlugin) {
        this.kinesisOperations = kinesisOperations
        streams = mapOf(options.kinesisStreamName to KinesisKtorStream(shardCount = 1))
    }
    install(StsKtorPlugin) {
        this.stsOperations = stsOperations
    }

    routing {
        post("/coverage/email") {
            val request = call.receive<SendEmailExampleRequest>().validated()
            val response = call.application.ses().sendEmail(
                SesEmailRequest(
                    destination = SesEmailAddressSet(to = listOf(request.to)),
                    subject = request.subject,
                    body = SesEmailBody(text = request.text),
                ),
            )
            call.respond(SendEmailExampleResponse(response.messageId().orEmpty()))
        }

        post("/coverage/notifications") {
            val request = call.receive<PublishNotificationExampleRequest>().validated()
            val response = call.application.sns().publish(
                SnsPublishRequest(
                    topicArn = options.snsTopicArn,
                    message = request.message,
                    subject = request.subject,
                ),
            )
            call.respond(PublishNotificationExampleResponse(response.messageId().orEmpty()))
        }

        post("/coverage/metrics") {
            val request = call.receive<MetricExampleRequest>().validated()
            val response = call.application.cloudWatch().putMetricData(
                options.namespace,
                listOf(
                    MetricDatum.builder()
                        .metricName(request.metricName)
                        .value(request.value)
                        .unit(StandardUnit.COUNT)
                        .build(),
                ),
            )
            call.respond(MetricExampleResponse(options.namespace, response.size))
        }

        post("/coverage/logs") {
            val request = call.receive<LogEventExampleRequest>().validated()
            val response = call.application.cloudWatchLogs().putLogEvents(
                options.logStream,
                listOf(
                    InputLogEvent.builder()
                        .message(request.message)
                        .timestamp(System.currentTimeMillis())
                        .build(),
                ),
            )
            call.respond(
                LogEventExampleResponse(
                    logGroupName = options.logStream.logGroupName,
                    logStreamName = options.logStream.logStreamName,
                    acceptedCount = response.size,
                ),
            )
        }

        post("/coverage/stream-records") {
            val request = call.receive<StreamRecordExampleRequest>().validated()
            val response = call.application.kinesis().putRecord(
                KinesisPutRecordRequest(
                    streamName = options.kinesisStreamName,
                    partitionKey = request.partitionKey,
                    data = SdkBytes.fromUtf8String(request.data),
                ),
            )
            call.respond(
                StreamRecordExampleResponse(
                    sequenceNumber = response.sequenceNumber().orEmpty(),
                    shardId = response.shardId().orEmpty(),
                ),
            )
        }

        get("/coverage/identity") {
            val response = call.application.sts().callerIdentity()
            call.respond(
                CallerIdentityExampleResponse(
                    account = response.account().orEmpty(),
                    arn = response.arn().orEmpty(),
                    userId = response.userId().orEmpty(),
                ),
            )
        }
    }
}

/**
 * Static resource names used by the service coverage routes.
 */
data class ServiceCoverageExampleOptions(
    val namespace: String = "Bluetape4k/AwsKtorExamples",
    val logStream: CloudWatchLogStream = CloudWatchLogStream("bluetape4k-aws-ktor-examples", "service-coverage"),
    val kinesisStreamName: String = "service-coverage-events",
    val snsTopicArn: String = "arn:aws:sns:us-east-1:000000000000:service-coverage",
): Serializable {
    init {
        namespace.requireNotBlank("namespace")
        kinesisStreamName.requireNotBlank("kinesisStreamName")
        snsTopicArn.requireNotBlank("snsTopicArn")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Request body for the SES/v2 email route. */
data class SendEmailExampleRequest(
    val to: String,
    val subject: String,
    val text: String,
): Serializable {
    fun validated(): SendEmailExampleRequest = apply {
        to.requireNotBlank("to")
        subject.requireNotBlank("subject")
        text.requireNotBlank("text")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Response body for the SES/v2 email route. */
data class SendEmailExampleResponse(val messageId: String): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Request body for the SNS publish route. */
data class PublishNotificationExampleRequest(
    val message: String,
    val subject: String? = null,
): Serializable {
    fun validated(): PublishNotificationExampleRequest = apply {
        message.requireNotBlank("message")
        subject?.requireNotBlank("subject")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Response body for the SNS publish route. */
data class PublishNotificationExampleResponse(val messageId: String): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Request body for the CloudWatch metric route. */
data class MetricExampleRequest(
    val metricName: String,
    val value: Double,
): Serializable {
    fun validated(): MetricExampleRequest = apply {
        metricName.requireNotBlank("metricName")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Response body for the CloudWatch metric route. */
data class MetricExampleResponse(
    val namespace: String,
    val acceptedCount: Int,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Request body for the CloudWatch Logs route. */
data class LogEventExampleRequest(val message: String): Serializable {
    fun validated(): LogEventExampleRequest = apply {
        message.requireNotBlank("message")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Response body for the CloudWatch Logs route. */
data class LogEventExampleResponse(
    val logGroupName: String,
    val logStreamName: String,
    val acceptedCount: Int,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Request body for the Kinesis put-record route. */
data class StreamRecordExampleRequest(
    val partitionKey: String,
    val data: String,
): Serializable {
    fun validated(): StreamRecordExampleRequest = apply {
        partitionKey.requireNotBlank("partitionKey")
        data.requireNotBlank("data")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Response body for the Kinesis put-record route. */
data class StreamRecordExampleResponse(
    val sequenceNumber: String,
    val shardId: String,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Response body for the STS identity route. */
data class CallerIdentityExampleResponse(
    val account: String,
    val arn: String,
    val userId: String,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
