package io.bluetape4k.aws.spring.sns

import io.bluetape4k.assertions.shouldBeEqualTo
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class SnsHttpMessageServletFilterTest {

    @Test
    fun `replayable servlet stream reports data before eof`() {
        val support = SnsHttpMessageResolverSupport(
            properties = SnsHttpEndpointProperties(
                verificationRequired = false,
                allowStructuralOnly = true,
                expectedTopicArns = setOf(TOPIC_ARN),
            ),
        )
        val request = MockHttpServletRequest().apply {
            addHeader(SnsHttpMessageResolverSupport.SNS_MESSAGE_TYPE_HEADER, "Notification")
            setContent(notificationJson.toByteArray())
        }
        val response = MockHttpServletResponse()
        val events = mutableListOf<String>()

        SnsHttpMessageServletFilter(support).doFilter(
            request,
            response,
        ) { downstream, _ ->
            lateinit var stream: ServletInputStream
            stream = downstream.inputStream
            stream.setReadListener(
                object : ReadListener {
                    override fun onDataAvailable() {
                        events += "data"
                        while (!stream.isFinished) stream.read()
                    }

                    override fun onAllDataRead() {
                        events += "all"
                    }

                    override fun onError(cause: Throwable) {
                        events += "error"
                    }
                },
            )
        }

        events shouldBeEqualTo listOf("data", "all")
        response.status shouldBeEqualTo 200
    }

    companion object {
        private const val TOPIC_ARN = "arn:aws:sns:us-west-2:123456789012:MyTopic"
        private val notificationJson =
            """
            {
              "Type" : "Notification",
              "MessageId" : "22b80b92-fdea-4c2c-8f9d-bdfb0c7bf324",
              "TopicArn" : "$TOPIC_ARN",
              "Message" : "hello",
              "Timestamp" : "2012-05-02T00:54:06.655Z",
              "SignatureVersion" : "2",
              "Signature" : "signature-2",
              "SigningCertURL" : "https://sns.us-west-2.amazonaws.com/SimpleNotificationService.pem"
            }
            """.trimIndent()
    }
}
