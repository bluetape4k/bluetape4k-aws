@file:Suppress("DEPRECATION")

package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.testcontainers.aws.LocalStackServer
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import java.util.UUID

class SqsCoroutinesTemplateLocalStackTest {

    companion object {
        private val localStack: LocalStackServer = LocalStackServer().withServices("sqs")

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            localStack.start()
        }

        @JvmStatic
        @AfterAll
        fun afterAll() {
            localStack.stop()
        }
    }

    private fun contextRunner(): ApplicationContextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                SqsAutoConfiguration::class.java,
            )
        )
        .withBean(AwsCredentialsProvider::class.java, { localStack.getCredentialProvider() })
        .withPropertyValues(
            "bluetape4k.aws.sqs.region=${localStack.regionName}",
            "bluetape4k.aws.sqs.endpoint-override=${localStack.awsEndpoint}",
            "bluetape4k.aws.sqs.listener.enabled=false",
        )

    @Test
    fun `create send receive and delete message through SqsOperations`() {
        contextRunner().run { context ->
            val operations = context.getBean(SqsOperations::class.java)

            runBlocking {
                val queueUrl = operations.createQueue("template-${UUID.randomUUID()}")
                val sent = operations.send(queueUrl, "hello sqs")
                assertThat(sent.messageId()).isNotBlank()

                val received = operations.receive(queueUrl, maxMessages = 1, waitTimeSeconds = 1)
                assertThat(received).hasSize(1)
                assertThat(received.single().body).isEqualTo("hello sqs")

                operations.delete(queueUrl, received.single().receiptHandle)
                assertThat(operations.receive(queueUrl, maxMessages = 1, waitTimeSeconds = 1)).isEmpty()
            }
        }
    }

    @Test
    fun `receiveFlow emits messages without deleting automatically`() {
        contextRunner().run { context ->
            val operations = context.getBean(SqsOperations::class.java)

            runBlocking {
                val queueUrl = operations.createQueue("flow-${UUID.randomUUID()}")
                operations.send(queueUrl, "flow sqs")

                val received = operations.receiveFlow(queueUrl, maxMessages = 1, waitTimeSeconds = 1).first()
                assertThat(received.body).isEqualTo("flow sqs")

                operations.changeVisibility(queueUrl, received.receiptHandle, 0)
                val receivedAgain = operations.receive(queueUrl, maxMessages = 1, waitTimeSeconds = 1)
                assertThat(receivedAgain.map { it.body }).contains("flow sqs")
                operations.delete(queueUrl, receivedAgain.single().receiptHandle)
            }
        }
    }
}
