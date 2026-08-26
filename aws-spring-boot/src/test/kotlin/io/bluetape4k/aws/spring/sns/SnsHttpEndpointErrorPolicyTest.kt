package io.bluetape4k.aws.spring.sns

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class SnsHttpEndpointErrorPolicyTest {

    @Test
    fun `rejection diagnostics keep only bounded safe fields`() {
        val policyLogger = LoggerFactory.getLogger(SnsHttpEndpointErrorPolicy::class.java) as Logger
        val appender = ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        val previousLevel = policyLogger.level
        policyLogger.addAppender(appender)
        policyLogger.level = Level.WARN
        try {
            SnsHttpEndpointErrorPolicy.record(
                SnsHttpEndpointErrorPolicy.Decision(400, "invalid-input"),
                size = SnsHttpMessageLimits.MAX_READ_BYTES + 1024,
                messageType = "Notification",
            )

            val message = appender.list.single().formattedMessage
            message shouldContain "category=invalid-input"
            message shouldContain "status=400"
            message shouldContain "size=${SnsHttpMessageLimits.MAX_READ_BYTES}"
            message shouldContain "type=Notification"
            message shouldNotContain "token"
            message shouldNotContain "signature"
            message shouldNotContain "arn:"
        } finally {
            policyLogger.detachAppender(appender)
            policyLogger.level = previousLevel
        }
    }

    @Test
    fun `servlet body limit is classified consistently`() {
        SnsHttpEndpointErrorPolicy.classify(SnsHttpBodyLimitException(SnsHttpMessageLimits.MAX_READ_BYTES))
            .status shouldBeEqualTo 400
        SnsHttpEndpointErrorPolicy.classify(SnsHttpBodyLimitException(SnsHttpMessageLimits.MAX_READ_BYTES))
            .category shouldBeEqualTo "body-limit"
    }
}
