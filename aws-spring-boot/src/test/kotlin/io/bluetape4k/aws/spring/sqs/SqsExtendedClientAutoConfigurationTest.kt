package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.s3.S3AutoConfiguration
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class SqsExtendedClientAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                SqsAutoConfiguration::class.java,
                S3AutoConfiguration::class.java,
                SqsMicrometerAutoConfiguration::class.java,
                SqsExtendedClientAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues(
            "bluetape4k.aws.sqs.region=us-east-1",
            "bluetape4k.aws.s3.region=us-east-1",
        )

    @Test
    fun `extended client is opt in and uses full bounded capabilities`() {
        contextRunner.run { context ->
            context.getBeansOfType(SqsExtendedClientOperations::class.java).size shouldBeEqualTo 0
        }

        contextRunner
            .withPropertyValues("bluetape4k.aws.sqs.extended.enabled=true")
            .run { context ->
                context.getBeansOfType(SqsExtendedClientOperations::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(SqsExtendedClient::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(SqsFullRequestOperations::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `insufficient Spring shutdown margin leaves extended path disabled`() {
        contextRunner
            .withPropertyValues(
                "bluetape4k.aws.sqs.extended.enabled=true",
                "spring.lifecycle.timeout-per-shutdown-phase=10s",
            )
            .run { context ->
                context.getBeansOfType(SqsExtendedClientOperations::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(SqsExtendedClientLifecycle::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `markerless custom SQS delegate fails closed without extended client`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sqs.extended.enabled=true")
            .withBean(SqsOperations::class.java, { NoopSqsOperations })
            .run { context ->
                context.getBeansOfType(SqsOperations::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(SqsExtendedClientOperations::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `extended micrometer path preserves full request marker`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.sqs.extended.enabled=true")
            .withBean(SimpleMeterRegistry::class.java, { SimpleMeterRegistry() })
            .run { context ->
                context.getBeansOfType(MicrometerFullRequestSqsOperations::class.java).size shouldBeEqualTo 1
                context.getBean(SqsFullRequestOperations::class.java).javaClass shouldBeEqualTo
                    MicrometerFullRequestSqsOperations::class.java
                context.getBeansOfType(SqsExtendedClientOperations::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `user extended client backs off auto configuration`() {
        val userClient = mockk<SqsExtendedClientOperations>()
        contextRunner
            .withPropertyValues("bluetape4k.aws.sqs.extended.enabled=true")
            .withBean(SqsExtendedClientOperations::class.java, { userClient })
            .run { context ->
                context.getBeansOfType(SqsExtendedClientOperations::class.java).size shouldBeEqualTo 1
                context.getBean(SqsExtendedClientOperations::class.java) shouldBeSameInstanceAs userClient
            }
    }
}
