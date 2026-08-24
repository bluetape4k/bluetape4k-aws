package io.bluetape4k.aws.spring.connection

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.testcontainers.aws.FlociServer
import io.bluetape4k.testcontainers.aws.LocalStackServer
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.service.connection.ConnectionDetailsFactory
import org.springframework.boot.testcontainers.service.connection.ContainerConnectionDetailsFactory
import org.springframework.core.io.support.SpringFactoriesLoader
import org.testcontainers.containers.Container
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.lang.reflect.ParameterizedType
import java.net.URI

@Suppress("DEPRECATION")
class AwsServiceConnectionDetailsFactoryTest {

    @Test
    fun `five factories keep service names required SDK classes and exact detail interfaces`() {
        val contracts = listOf(
            Triple(S3ContainerConnectionDetailsFactory::class.java, "s3", S3ConnectionDetails::class.java),
            Triple(SqsContainerConnectionDetailsFactory::class.java, "sqs", SqsConnectionDetails::class.java),
            Triple(SnsContainerConnectionDetailsFactory::class.java, "sns", SnsConnectionDetails::class.java),
            Triple(
                DynamoDbContainerConnectionDetailsFactory::class.java,
                "dynamodb",
                DynamoDbConnectionDetails::class.java,
            ),
            Triple(
                KinesisContainerConnectionDetailsFactory::class.java,
                "kinesis",
                KinesisConnectionDetails::class.java,
            ),
        )
        val requiredClasses = listOf(
            "software.amazon.awssdk.services.s3.S3Client",
            "software.amazon.awssdk.services.sqs.SqsClient",
            "software.amazon.awssdk.services.sns.SnsClient",
            "software.amazon.awssdk.services.dynamodb.DynamoDbClient",
            "software.amazon.awssdk.services.kinesis.KinesisClient",
        )

        contracts.forEachIndexed { index, (factoryType, serviceName, detailsType) ->
            val factory = factoryType.getDeclaredConstructor().newInstance()
            val generic = factoryType.genericSuperclass as ParameterizedType
            val containerType = generic.actualTypeArguments[0] as ParameterizedType
            containerType.rawType shouldBeEqualTo Container::class.java
            generic.actualTypeArguments[1] shouldBeEqualTo detailsType

            val namesField = ContainerConnectionDetailsFactory::class.java.getDeclaredField("connectionNames")
            namesField.trySetAccessible().shouldBeTrue()
            @Suppress("UNCHECKED_CAST")
            val connectionNames = namesField.get(factory) as List<String>
            connectionNames shouldBeEqualTo listOf(serviceName)

            val requiredField = ContainerConnectionDetailsFactory::class.java.getDeclaredField("requiredClassNames")
            requiredField.trySetAccessible().shouldBeTrue()
            val required = requiredField.get(factory) as Array<*>
            required.single() shouldBeEqualTo requiredClasses[index]
        }
    }

    @Test
    fun `spring factories registers every AWS container factory exactly once`() {
        val names = SpringFactoriesLoader.loadFactoryNames(
            ContainerConnectionDetailsFactory::class.java,
            javaClass.classLoader,
        )
        val expected = listOf(
            "io.bluetape4k.aws.spring.connection.S3ContainerConnectionDetailsFactory",
            "io.bluetape4k.aws.spring.connection.SqsContainerConnectionDetailsFactory",
            "io.bluetape4k.aws.spring.connection.SnsContainerConnectionDetailsFactory",
            "io.bluetape4k.aws.spring.connection.DynamoDbContainerConnectionDetailsFactory",
            "io.bluetape4k.aws.spring.connection.KinesisContainerConnectionDetailsFactory",
        )

        expected.forEach { name -> names.count { it == name } shouldBeEqualTo 1 }

        val runtimeNames = SpringFactoriesLoader.loadFactoryNames(
            ConnectionDetailsFactory::class.java,
            javaClass.classLoader,
        )
        expected.forEach { name -> runtimeNames.count { it == name } shouldBeEqualTo 1 }
    }

    @Test
    fun `allow-list accepts only Floci and LocalStack concrete wrappers`() {
        isSupportedAwsEmulator(FlociServer()).shouldBeTrue()
        isSupportedAwsEmulator(LocalStackServer()).shouldBeTrue()
        isSupportedAwsEmulator(GenericContainer(DockerImageName.parse("alpine:3.20"))).shouldBeFalse()

        val arbitrary = mockk<GenericContainer<*>>(relaxed = true)
        isSupportedAwsEmulator(arbitrary).shouldBeFalse()
    }

    @Test
    fun `unsupported and malformed values fail closed without exposing credentials`() {
        val unsupported = GenericContainer(DockerImageName.parse("alpine:3.20"))
        snapshotAwsServiceConnection(unsupported, "s3").shouldBeNull()

        val floci = mockk<FlociServer>(relaxed = true)
        every { floci.awsEndpoint } returns URI.create("/relative")
        every { floci.regionName } returns "us-east-1"
        every { floci.awsAccessKey } returns "access-secret-value"
        every { floci.awsSecretKey } returns "secret-value"

        val error = io.bluetape4k.assertions.assertFailsWith<AwsServiceConnectionConfigurationException> {
            snapshotAwsServiceConnection(floci, "s3")
        }
        error.reason shouldBeEqualTo AwsServiceConnectionConfigurationException.Reason.MALFORMED_DETAILS
        error.serviceNames shouldContain "s3"
        error.message.orEmpty() shouldBeEqualTo
            "AWS ServiceConnection configuration failed: reason=MALFORMED_DETAILS, " +
            "services=[s3], candidates=1"
        error.message.orEmpty().contains("access-secret-value").shouldBeFalse()
        error.message.orEmpty().contains("secret-value").shouldBeFalse()
    }
}
