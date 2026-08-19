package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.sns.SnsAutoConfiguration
import io.bluetape4k.aws.spring.sns.SnsOperations
import io.bluetape4k.aws.spring.sns.SnsPublishRequest
import io.bluetape4k.aws.spring.test.AwsSpringBootTestEmulator
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import java.util.UUID
import tools.jackson.databind.ObjectMapper

/** Floci-first SNS fanout과 SQS envelope 변환을 함께 검증합니다. */
class SqsSnsMessageConverterAwsEmulatorTest {

    companion object {
        private val awsEmulator by lazy {
            AwsSpringBootTestEmulator.get("sns", "sqs")
        }
    }

    private fun contextRunner(): ApplicationContextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                SnsAutoConfiguration::class.java,
                SqsAutoConfiguration::class.java,
            )
        )
        .withBean(AwsCredentialsProvider::class.java, { awsEmulator.getCredentialProvider() })
        .withPropertyValues(
            "bluetape4k.aws.sns.region=${awsEmulator.regionName}",
            "bluetape4k.aws.sns.endpoint-override=${awsEmulator.awsEndpoint}",
            "bluetape4k.aws.sqs.region=${awsEmulator.regionName}",
            "bluetape4k.aws.sqs.endpoint-override=${awsEmulator.awsEndpoint}",
            "bluetape4k.aws.sqs.listener.enabled=false",
        )

    @Test
    fun `Floci SNS fanout converts typed payload and preserves metadata`() {
        contextRunner().run { context ->
            val sns = context.getBean(SnsOperations::class.java)
            val sqs = context.getBean(SqsOperations::class.java)
            val objectMapper = ObjectMapper()

            runSuspendIO {
                val sqsClient = sqsClient()
                try {
                    val topicArn = sns.createTopic("converter-${Base58.randomString(8)}")
                    val queueUrl = sqs.createQueue("converter-${Base58.randomString(8)}")
                    val queueArn = queueArn(sqsClient, queueUrl)
                    sqsClient.setQueueAttributes {
                        it.queueUrl(queueUrl)
                        it.attributes(mapOf(QueueAttributeName.POLICY to queuePolicy(queueArn, topicArn)))
                    }.await()

                    context.getBean(software.amazon.awssdk.services.sns.SnsAsyncClient::class.java)
                        .subscribe {
                            it.topicArn(topicArn)
                            it.protocol("sqs")
                            it.endpoint(queueArn)
                            it.returnSubscriptionArn(true)
                        }
                        .await()

                    val orderId = "order-${Base58.randomString(16)}"
                    sns.publish(
                        SnsPublishRequest(
                            topicArn = topicArn,
                            subject = "Order created",
                            message = orderId,
                            messageAttributes = mapOf(
                                "trace" to MessageAttributeValue.builder()
                                    .dataType("String")
                                    .stringValue("trace-${Base58.randomString(8)}")
                                    .build(),
                            ),
                        )
                    )

                    val received = sqs.receive(queueUrl, maxMessages = 1, waitTimeSeconds = 5)
                    received shouldHaveSize 1
                    val notification = SnsMessageConverter(objectMapper)
                        .convertNotification(received.single(), String::class.java)

                    notification.message shouldBeEqualTo orderId
                    notification.topicArn shouldBeEqualTo topicArn
                    notification.subject shouldBeEqualTo "Order created"
                    notification.messageAttributes.getValue("trace").type shouldBeEqualTo "String"
                    notification.sqs.queueUrl shouldBeEqualTo queueUrl
                    notification.rawEnvelope shouldContain "Notification"
                } finally {
                    sqsClient.close()
                }
            }
        }
    }

    private fun sqsClient(): SqsAsyncClient = SqsAsyncClient.builder()
        .credentialsProvider(awsEmulator.getCredentialProvider())
        .region(Region.of(awsEmulator.regionName))
        .endpointOverride(awsEmulator.awsEndpoint)
        .build()

    private suspend fun queueArn(client: SqsAsyncClient, queueUrl: String): String =
        requireNotNull(
            client.getQueueAttributes {
                it.queueUrl(queueUrl)
                it.attributeNames(QueueAttributeName.QUEUE_ARN)
            }.await().attributes()[QueueAttributeName.QUEUE_ARN]
        ) {
            "QueueArn attribute must be returned by the AWS emulator."
        }

    private fun queuePolicy(queueArn: String, topicArn: String): String =
        """
        {
          "Version":"2012-10-17",
          "Statement":[{
            "Effect":"Allow",
            "Principal":"*",
            "Action":"sqs:SendMessage",
            "Resource":"$queueArn",
            "Condition":{"ArnEquals":{"aws:SourceArn":"$topicArn"}}
          }]
        }
        """.trimIndent()

}
