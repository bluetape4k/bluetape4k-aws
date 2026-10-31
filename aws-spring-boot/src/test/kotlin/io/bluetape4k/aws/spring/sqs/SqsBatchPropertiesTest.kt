package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.codec.Base58
import io.bluetape4k.io.serializer.JdkBinarySerializer
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.minutes
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import java.time.Duration

class SqsBatchPropertiesTest {

    companion object: KLogging() {
        private val contextRunner = ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration::class.java)
    }

    @Test
    fun `batch properties expose safe defaults`() {
        val properties = SqsBatchProperties()

        log.debug { "properties=$properties" }
        properties.enabled.shouldBeFalse()
        properties.maxBatchSize shouldBeEqualTo 10
        properties.flushInterval shouldBeEqualTo 200.millis()
        properties.maxEntriesPerCall shouldBeEqualTo 1_000
        properties.maxInFlightEntries shouldBeEqualTo 100
        properties.schedulerThreads shouldBeEqualTo 1
        properties.shutdownTimeout shouldBeEqualTo 5.seconds()
        roundTrip(properties) shouldBeEqualTo properties

        SqsBatchProperties::class.java.getDeclaredField("serialVersionUID")
            .apply { isAccessible = true }
            .getLong(null) shouldBeEqualTo 1L
    }

    @Test
    fun `batch properties accept inclusive boundaries`() {
        SqsBatchProperties(
            enabled = true,
            maxBatchSize = 1,
            flushInterval = 1.millis(),
            maxEntriesPerCall = 1,
            maxInFlightEntries = 1,
            schedulerThreads = 1,
            shutdownTimeout = 1.millis(),
        )
        SqsBatchProperties(
            enabled = true,
            maxBatchSize = 10,
            flushInterval = 1.minutes(),
            maxEntriesPerCall = 10_000,
            maxInFlightEntries = 10_000,
            schedulerThreads = 16,
            shutdownTimeout = 1.minutes(),
        )
    }

    @Test
    fun `batch properties reject every value outside its range without exposing the value`() {
        val cases = listOf(
            Triple("max-batch-size", "0") { SqsBatchProperties(maxBatchSize = 0) },
            Triple("max-batch-size", "11") { SqsBatchProperties(maxBatchSize = 11) },
            Triple("flush-interval", "PT0S") { SqsBatchProperties(flushInterval = Duration.ZERO) },
            Triple("flush-interval", "PT1M0.001S") {
                SqsBatchProperties(flushInterval = 1.minutes() + 1.millis())
            },
            Triple("max-entries-per-call", "0") { SqsBatchProperties(maxEntriesPerCall = 0) },
            Triple("max-entries-per-call", "10001") { SqsBatchProperties(maxEntriesPerCall = 10_001) },
            Triple("max-in-flight-entries", "0") { SqsBatchProperties(maxInFlightEntries = 0) },
            Triple("max-in-flight-entries", "10001") { SqsBatchProperties(maxInFlightEntries = 10_001) },
            Triple("scheduler-threads", "0") { SqsBatchProperties(schedulerThreads = 0) },
            Triple("scheduler-threads", "17") { SqsBatchProperties(schedulerThreads = 17) },
            Triple("shutdown-timeout", "PT0S") { SqsBatchProperties(shutdownTimeout = Duration.ZERO) },
            Triple("shutdown-timeout", "PT1M0.001S") {
                SqsBatchProperties(shutdownTimeout = 1.minutes() + 1.millis())
            },
        )

        cases.forEach { (propertyToken, rawValue, create) ->
            val error = assertFailsWith<IllegalArgumentException> { create() }
            error.message shouldContain "$SQS_BATCH_PROPERTIES_PREFIX.$propertyToken"
            error.message shouldNotContain rawValue
        }
    }

    @Test
    fun `batch-only cross constraints do not reject direct mode`() {
        SqsBatchProperties(
            enabled = false,
            maxBatchSize = 10,
            maxInFlightEntries = 1,
            flushInterval = 5.seconds(),
            shutdownTimeout = 1.millis(),
        ).maxInFlightEntries shouldBeEqualTo 1

        assertFailsWith<IllegalArgumentException> {
            SqsBatchProperties(enabled = true, maxBatchSize = 10, maxInFlightEntries = 9)
        }.message shouldContain "$SQS_BATCH_PROPERTIES_PREFIX.max-in-flight-entries"

        assertFailsWith<IllegalArgumentException> {
            SqsBatchProperties(
                enabled = true,
                flushInterval = 2.seconds(),
                shutdownTimeout = 1.seconds(),
            )
        }.message shouldContain "$SQS_BATCH_PROPERTIES_PREFIX.shutdown-timeout"
    }

    @Test
    fun `invalid configuration binding fails before bean use without leaking unrelated secret`() {
        val secret = Base58.randomString(16)

        contextRunner
            .withPropertyValues(
                "$SQS_BATCH_PROPERTIES_PREFIX.enabled=true",
                "$SQS_BATCH_PROPERTIES_PREFIX.max-batch-size=10",
                "$SQS_BATCH_PROPERTIES_PREFIX.max-in-flight-entries=9",
                "test.secret=$secret",
            )
            .run { context ->
                val failure = context.startupFailure.shouldNotBeNull()
                val rendered = generateSequence(failure as Throwable?) { it.cause }
                    .joinToString(separator = "\n") { "${it::class.java.name}: ${it.message}" }
                rendered shouldContain "$SQS_BATCH_PROPERTIES_PREFIX.max-in-flight-entries"
                rendered shouldNotContain secret
            }
    }

    @Test
    fun `every invalid property binding fails with only the property token`() {
        val cases = listOf(
            BindingFailureCase("max-batch-size", "0"),
            BindingFailureCase("max-batch-size", "11"),
            BindingFailureCase("flush-interval", "0ms"),
            BindingFailureCase("flush-interval", "60001ms"),
            BindingFailureCase("max-entries-per-call", "0"),
            BindingFailureCase("max-entries-per-call", "10001"),
            BindingFailureCase("max-in-flight-entries", "0"),
            BindingFailureCase("max-in-flight-entries", "10001"),
            BindingFailureCase("scheduler-threads", "0"),
            BindingFailureCase("scheduler-threads", "17"),
            BindingFailureCase("shutdown-timeout", "0ms"),
            BindingFailureCase("shutdown-timeout", "60001ms"),
        )

        cases.forEach { case ->
            assertBindingFailure(
                propertyToken = case.propertyToken,
                rawValue = case.rawValue,
                propertyValues = arrayOf(
                    "$SQS_BATCH_PROPERTIES_PREFIX.${case.propertyToken}=${case.rawValue}",
                ),
            )
        }
        assertBindingFailure(
            propertyToken = "max-in-flight-entries",
            rawValue = "9",
            propertyValues = arrayOf(
                "$SQS_BATCH_PROPERTIES_PREFIX.enabled=true",
                "$SQS_BATCH_PROPERTIES_PREFIX.max-batch-size=10",
                "$SQS_BATCH_PROPERTIES_PREFIX.max-in-flight-entries=9",
            ),
        )
        assertBindingFailure(
            propertyToken = "shutdown-timeout",
            rawValue = "1000ms",
            propertyValues = arrayOf(
                "$SQS_BATCH_PROPERTIES_PREFIX.enabled=true",
                "$SQS_BATCH_PROPERTIES_PREFIX.flush-interval=2000ms",
                "$SQS_BATCH_PROPERTIES_PREFIX.shutdown-timeout=1000ms",
            ),
        )
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SqsBatchProperties::class)
    private class TestConfiguration

    private fun assertBindingFailure(
        propertyToken: String,
        rawValue: String,
        propertyValues: Array<String>,
    ) {
        contextRunner.withPropertyValues(*propertyValues).run { context ->
            val failure = context.startupFailure.shouldNotBeNull()
            val rendered = generateSequence(failure as Throwable?) { it.cause }
                .joinToString(separator = "\n") { "${it::class.java.name}: ${it.message}" }
            rendered shouldContain "$SQS_BATCH_PROPERTIES_PREFIX.$propertyToken"
            rendered shouldNotContain rawValue
        }
    }

    private data class BindingFailureCase(
        val propertyToken: String,
        val rawValue: String,
    )

    private val jdkSerializer = JdkBinarySerializer(objectInputFilter = null)

    @Suppress("UNCHECKED_CAST")
    private fun <T: Any> roundTrip(value: T): T {
        val bytes = jdkSerializer.serialize(value)
        return jdkSerializer.deserialize<T>(bytes).shouldNotBeNull()
    }
}
