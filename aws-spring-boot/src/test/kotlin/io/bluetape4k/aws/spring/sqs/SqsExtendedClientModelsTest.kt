@file:Suppress("MaxLineLength")

package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.assertions.shouldNotContentEqual
import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import java.lang.reflect.Modifier

class SqsExtendedClientModelsTest {

    companion object: KLogging()

    @Test
    fun `message attributes clone binary bytes and redact their display`() {
        val secret = "attribute-${Base58.randomString(16)}"
        val source = MessageAttributeValue.builder()
            .dataType("Binary")
            .binaryValue(SdkBytes.fromUtf8String(secret))
            .build()
        val attribute = SqsExtendedMessageAttribute.create(source)

        val first = attribute.binaryValue ?: error("binary value is required")
        first[0] = first[0].inc()

        val second = attribute.binaryValue ?: error("binary value is required")

        second shouldNotContentEqual first
        attribute.dataType shouldBeEqualTo "Binary"
        attribute.toString() shouldNotContain secret
    }

    @Test
    fun `message attribute rejects control characters`() {
        val invalidDataType = MessageAttributeValue.builder()
            .dataType("String\n")
            .stringValue("safe")
            .build()

        runCatching {
            SqsExtendedMessageAttribute.create(invalidDataType)
        }.exceptionOrNull()?.javaClass shouldBeEqualTo IllegalArgumentException::class.java

        val invalidValue = MessageAttributeValue.builder()
            .dataType("String")
            .stringValue("unsafe\rvalue")
            .build()

        runCatching {
            SqsExtendedMessageAttribute.create(invalidValue)
        }.exceptionOrNull()?.javaClass shouldBeEqualTo IllegalArgumentException::class.java
    }

    @Test
    fun `safe models do not expose generated copy or public constructors`() {
        SqsExtendedReceivedMessage::class.java.declaredConstructors
            .filter {
                Modifier.isPublic(it.modifiers) &&
                        it.parameterTypes.none { parameter -> parameter.name == "kotlin.jvm.internal.DefaultConstructorMarker" }
            }
            .size shouldBeEqualTo 0
        SqsExtendedReceivedMessage::class.java.declaredMethods
            .count { it.name == "copy" } shouldBeEqualTo 0

        val request = SqsExtendedSendRequest(
            request = SqsSendRequest(
                queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789012/${Base58.randomString(16)}",
                body = "payload-${Base58.randomString(16)}",
                messageGroupId = "group-${Base58.randomString(16)}",
                messageDeduplicationId = "dedup-${Base58.randomString(16)}",
            ),
            contentType = "application/json",
            idempotencyKey = Base58.randomString(16),
        )
        log.debug { "request=$request" }
        request.toString().let { req ->
            req shouldNotContain request.request.body
            req shouldNotContain request.request.queueUrl
            req shouldContain "contentTypePresent=true"
            req shouldContain "idempotencyKeyPresent=true"
        }
    }
}
