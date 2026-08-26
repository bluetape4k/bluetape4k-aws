package io.bluetape4k.aws.spring.sns

import io.bluetape4k.aws.spring.sqs.SnsMessageAttribute
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper

class SnsHttpMessagePayloadConverterTest {

    private val converter = SnsHttpMessagePayloadConverter(ObjectMapper())

    @Test
    fun `message attributes expose typed snapshots`() {
        val message = SnsHttpMessageParser.parse(
            notificationJson.replace(
                "\"UnsubscribeURL\"",
                "\"MessageAttributes\": { " +
                    "\"contentType\": { \"Type\": \"String\", \"Value\": \"application/json\" } }, " +
                    "\"UnsubscribeURL\"",
            )
        )

        message.messageAttributes["contentType"] shouldBeEqualTo
            SnsMessageAttribute("String", "application/json")
    }

    @Test
    fun `json payload converts with configured object mapper`() {
        converter.convert("{\"id\":7}", Map::class.java, "application/json") shouldBeEqualTo mapOf("id" to 7)
    }

    @Test
    fun `primary object mapper wins regardless of registration order`() {
        val secondary = ObjectMapper()
        val primary = ObjectMapper()
        val beanFactory = DefaultListableBeanFactory().apply {
            registerBeanDefinition(
                "secondaryObjectMapper",
                RootBeanDefinition(ObjectMapper::class.java) { secondary },
            )
            registerBeanDefinition(
                "primaryObjectMapper",
                RootBeanDefinition(ObjectMapper::class.java) { primary }.apply { isPrimary = true },
            )
        }

        findSnsObjectMapper(beanFactory) shouldBeSameInstanceAs primary
    }

    @Test
    fun `multiple non primary object mappers back off without relying on registration order`() {
        val beanFactory = DefaultListableBeanFactory().apply {
            registerSingleton("firstObjectMapper", ObjectMapper())
            registerSingleton("secondObjectMapper", ObjectMapper())
        }

        findSnsObjectMapper(beanFactory).shouldBeNull()
    }

    @Test
    fun `MVC and WebFlux contexts resolve the primary object mapper`() {
        WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SnsHttpEndpointWebMvcAutoConfiguration::class.java))
            .withUserConfiguration(MultipleObjectMapperConfiguration::class.java)
            .withPropertyValues(
                "bluetape4k.aws.sns.http-endpoints.verification-required=false",
                "bluetape4k.aws.sns.http-endpoints.allow-structural-only=true",
            )
            .run { context ->
                context.startupFailure.shouldBeNull()
                findSnsObjectMapper(context) shouldBeSameInstanceAs context.getBean("primaryObjectMapper")
            }

        ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SnsHttpEndpointWebFluxAutoConfiguration::class.java))
            .withUserConfiguration(MultipleObjectMapperConfiguration::class.java)
            .withPropertyValues(
                "bluetape4k.aws.sns.http-endpoints.verification-required=false",
                "bluetape4k.aws.sns.http-endpoints.allow-structural-only=true",
            )
            .run { context ->
                context.startupFailure.shouldBeNull()
                findSnsObjectMapper(context) shouldBeSameInstanceAs context.getBean("primaryObjectMapper")
            }
    }

    @Test
    fun `typed payload rejects non json nested media type`() {
        val error = assertFailsWith<ResponseStatusException> {
            converter.convert("not-json", Map::class.java, "text/plain")
        }

        error.statusCode shouldBeEqualTo HttpStatus.BAD_REQUEST
    }
}

@Configuration(proxyBeanMethods = false)
private class MultipleObjectMapperConfiguration {
    @Bean
    fun secondaryObjectMapper(): ObjectMapper = ObjectMapper()

    @Bean
    @Primary
    fun primaryObjectMapper(): ObjectMapper = ObjectMapper()
}

private val notificationJson: String =
    """
    {
      "Type" : "Notification",
      "MessageId" : "22b80b92-fdea-4c2c-8f9d-bdfb0c7bf324",
      "TopicArn" : "arn:aws:sns:us-west-2:123456789012:MyTopic",
      "Message" : "{\"orderId\":\"order-1\"}",
      "Timestamp" : "2012-05-02T00:54:06.655Z",
      "SignatureVersion" : "2",
      "Signature" : "signature-2",
      "SigningCertURL" : "https://sns.us-west-2.amazonaws.com/SimpleNotificationService.pem",
      "UnsubscribeURL" : "https://sns.us-west-2.amazonaws.com/?Action=Unsubscribe&SubscriptionArn=sub-1"
    }
    """.trimIndent()
