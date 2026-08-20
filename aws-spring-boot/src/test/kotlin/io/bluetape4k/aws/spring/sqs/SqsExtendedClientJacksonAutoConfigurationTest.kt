@file:Suppress("MaxLineLength")

package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.codec.Base58
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream

class SqsExtendedClientJacksonAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                SqsAutoConfiguration::class.java,
                SqsJacksonMessageConverterAutoConfiguration::class.java,
                SqsExtendedClientJacksonAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues("bluetape4k.aws.sqs.region=us-east-1")

    @Test
    fun `jackson auto configuration registers extended module after the existing converter`() {
        contextRunner
            .withBean(ObjectMapper::class.java, { ObjectMapper() })
            .run { context ->
                context.getBeansOfType(SqsMessageConverter::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(SqsExtendedClientJacksonModule::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `jackson auto configuration backs off when the module is supplied`() {
        val userModule = SqsExtendedClientJacksonModule()
        contextRunner
            .withBean(ObjectMapper::class.java, { ObjectMapper() })
            .withBean(SqsExtendedClientJacksonModule::class.java, { userModule })
            .run { context ->
                context.getBeansOfType(SqsExtendedClientJacksonModule::class.java).size shouldBeEqualTo 1
                context.getBean(SqsExtendedClientJacksonModule::class.java) shouldBeSameInstanceAs userModule
            }
    }

    @Test
    fun `supported module serializes safe fields and omits raw AWS and pointer secrets`() {
        val queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789012/${Base58.randomString(16)}"
        val body = "payload-${Base58.randomString(16)}"
        val request = SqsExtendedSendRequest(
            request = SqsSendRequest(queueUrl = queueUrl, body = body),
            contentType = "application/json",
            idempotencyKey = Base58.randomString(16),
        )
        val mapper = JsonMapper.builder()
            .addModule(SqsExtendedClientJacksonModule())
            .build()

        val json = mapper.writeValueAsString(request)

        json shouldContain "contentTypePresent"
        json shouldContain "idempotencyKeyPresent"
        json shouldNotContain body
        json shouldNotContain queueUrl
        json shouldNotContain "request"
    }

    @Test
    fun `extended public models are not Java serializable`() {
        val request = SqsExtendedSendRequest(
            request = SqsSendRequest(
                queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789012/${Base58.randomString(16)}",
                body = Base58.randomString(16),
            ),
        )
        assertFailsWith<java.io.NotSerializableException> {
            ObjectOutputStream(ByteArrayOutputStream()).use { stream -> stream.writeObject(request) }
        }
    }
}
