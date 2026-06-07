package io.bluetape4k.aws.spring.imds

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.imds.Ec2MetadataAsyncClient
import software.amazon.awssdk.imds.EndpointMode
import java.time.Duration

class ImdsAutoConfigurationTest {

    private val customClient = mockk<Ec2MetadataAsyncClient>(relaxed = true)
    private val customOperations = mockk<ImdsOperations>(relaxed = true)

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                ImdsAutoConfiguration::class.java,
            )
        )

    @BeforeEach
    fun resetMocks() {
        clearMocks(customClient, customOperations)
    }

    @Test
    fun `register IMDS client and operations`() {
        contextRunner.run { context ->
            context.getBeansOfType(Ec2MetadataAsyncClient::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(ImdsOperations::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(ImdsProperties::class.java).size shouldBeEqualTo 1
        }
    }

    @Test
    fun `back off when IMDS auto configuration disabled`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.imds.enabled=false")
            .run { context ->
                context.getBeansOfType(Ec2MetadataAsyncClient::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(ImdsOperations::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `custom IMDS client backs off auto configured client without startup calls`() {
        contextRunner
            .withBean(Ec2MetadataAsyncClient::class.java, { customClient })
            .run { context ->
                context.getBeansOfType(Ec2MetadataAsyncClient::class.java).size shouldBeEqualTo 1
                context.getBean(Ec2MetadataAsyncClient::class.java) shouldBeSameInstanceAs customClient
                context.getBeansOfType(ImdsOperations::class.java).size shouldBeEqualTo 1
                verify(exactly = 0) { customClient.get(any()) }
            }
    }

    @Test
    fun `custom IMDS operations backs off template`() {
        contextRunner
            .withBean(ImdsOperations::class.java, { customOperations })
            .run { context ->
                context.getBeansOfType(ImdsOperations::class.java).size shouldBeEqualTo 1
                context.getBean(ImdsOperations::class.java) shouldBeSameInstanceAs customOperations
            }
    }

    @Test
    fun `IMDS properties bind`() {
        contextRunner
            .withPropertyValues(
                "bluetape4k.aws.imds.endpoint=http://169.254.169.254",
                "bluetape4k.aws.imds.endpoint-mode=ipv6",
                "bluetape4k.aws.imds.token-ttl=5m",
                "bluetape4k.aws.imds.request-timeout=750ms",
                "bluetape4k.aws.imds.retries=1",
            )
            .run { context ->
                val properties = context.getBean(ImdsProperties::class.java)
                properties.endpoint.toString() shouldBeEqualTo "http://169.254.169.254"
                properties.endpointMode shouldBeEqualTo EndpointMode.IPV6
                properties.tokenTtl shouldBeEqualTo Duration.ofMinutes(5)
                properties.requestTimeout shouldBeEqualTo Duration.ofMillis(750)
                properties.retries shouldBeEqualTo 1
            }
    }

    @Test
    fun `IMDS property validation rejects non positive timeout`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.imds.request-timeout=0ms")
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                messages shouldContain "request-timeout must be positive"
            }
    }

    @Test
    fun `IMDS auto configuration backs off when SDK is absent`() {
        contextRunner
            .withClassLoader(FilteredClassLoader("software.amazon.awssdk.imds"))
            .run { context ->
                context.getBeansOfType(Ec2MetadataAsyncClient::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(ImdsOperations::class.java).size shouldBeEqualTo 0
            }
    }
}
