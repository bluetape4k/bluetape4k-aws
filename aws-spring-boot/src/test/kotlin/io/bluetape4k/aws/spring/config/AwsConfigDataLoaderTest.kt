package io.bluetape4k.aws.spring.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.aws.spring.parameterstore.ParameterStoreConfigDataLoader
import io.bluetape4k.aws.spring.parameterstore.ParameterStoreConfigDataSdkAdapter
import io.bluetape4k.aws.spring.s3.S3ConfigDataLoader
import io.bluetape4k.aws.spring.s3.S3ConfigDataSdkAdapter
import io.bluetape4k.aws.spring.secretsmanager.SecretsManagerConfigDataLoader
import io.bluetape4k.aws.spring.secretsmanager.SecretsManagerConfigDataSdkAdapter
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext
import org.springframework.boot.context.config.ConfigDataLoaderContext
import org.springframework.boot.context.config.ConfigDataLocation
import org.springframework.boot.context.config.ConfigDataResourceNotFoundException
import org.springframework.core.env.MapPropertySource
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.S3Exception

class AwsConfigDataLoaderTest {

    companion object: KLogging()

    private val bootstrap = mockk<ConfigurableBootstrapContext>(relaxed = true)

    @BeforeEach
    fun beforeEach() {
        clearMocks(bootstrap)
    }

    @Test
    fun `disabled resources return empty ConfigData without bootstrap client access`() {
        val context = contextOf(bootstrap)
        val resources = listOf(
            S3ConfigDataLoader(mockk(relaxed = true), bootstrap) to
                    resource("aws-s3:/bucket/key", disabled = true),
            ParameterStoreConfigDataLoader(mockk(relaxed = true), bootstrap) to
                    resource("aws-parameterstore:/app", disabled = true),
            SecretsManagerConfigDataLoader(mockk(relaxed = true), bootstrap) to
                    resource("aws-secretsmanager:secret", disabled = true),
        )

        resources.forEach { (loader, resource) ->
            val data = loader.load(context, resource).shouldNotBeNull()
            val source = data.propertySources.single() as MapPropertySource
            source.source.shouldBeEmpty()
        }

        verify(exactly = 0) { bootstrap.get<Any>(any()) }
    }

    @Test
    fun `S3 loader delegates to shared bootstrap client and returns one property source`() {
        val context = contextOf(bootstrap)
        val resource = resource("aws-s3:/bucket/application.json")
        mockkObject(S3ConfigDataSdkAdapter)
        try {
            every { S3ConfigDataSdkAdapter.load(bootstrap, resource) } returns mapOf("app.name" to "demo")

            val data = S3ConfigDataLoader(mockk(relaxed = true), bootstrap)
                .load(context, resource).shouldNotBeNull()

            val source = data.propertySources.single() as MapPropertySource

            log.debug { "source=$source" }
            source.name shouldBeEqualTo resource.toString()
            source.source shouldBeEqualTo mapOf("app.name" to "demo")

            verify(exactly = 1) { S3ConfigDataSdkAdapter.load(bootstrap, resource) }
        } finally {
            unmockkObject(S3ConfigDataSdkAdapter)
        }
    }

    @Test
    fun `optional not found is skipped but required not found preserves resource`() {
        val context = contextOf(bootstrap)
        val optional = resource("optional:aws-s3:/bucket/missing")
        val required = resource("aws-s3:/bucket/missing")
        mockkObject(S3ConfigDataSdkAdapter)

        try {
            every {
                S3ConfigDataSdkAdapter.load(any(), any())
            } throws NoSuchKeyException.builder().build()

            S3ConfigDataLoader(
                mockk(relaxed = true),
                bootstrap
            ).load(context, optional).shouldBeNull()

            val error = assertFailsWith<ConfigDataResourceNotFoundException> {
                S3ConfigDataLoader(mockk(relaxed = true), bootstrap)
                    .load(context, required)
            }
            error.resource shouldBeEqualTo required
        } finally {
            unmockkObject(S3ConfigDataSdkAdapter)
        }
    }

    @Test
    fun `authentication failure is sanitized and does not retain SDK message`() {
        val context = contextOf(bootstrap)
        val resource = resource("optional:aws-s3:/secret-bucket/secret-key")
        mockkObject(S3ConfigDataSdkAdapter)

        try {
            every {
                S3ConfigDataSdkAdapter.load(any(), any())
            } throws S3Exception.builder()
                .statusCode(403)
                .message("transport-secret-bucket")
                .build()

            val error = assertFailsWith<AwsConfigDataLoadException> {
                S3ConfigDataLoader(mockk(relaxed = true), bootstrap)
                    .load(context, resource)
            }
            log.debug { "error=$error" }
            error.toString() shouldNotContain "transport-secret-bucket"
            error.toString() shouldNotContain "secret-bucket"
            error.cause.shouldBeNull()
        } finally {
            unmockkObject(S3ConfigDataSdkAdapter)
        }
    }

    @Test
    fun `parameter and secret adapters preserve backend-specific optional policy`() {
        val context = contextOf(bootstrap)
        val parameter = resource("optional:aws-parameterstore:/missing")
        val secret = resource("optional:aws-secretsmanager:missing")
        mockkObject(ParameterStoreConfigDataSdkAdapter, SecretsManagerConfigDataSdkAdapter)

        try {
            every {
                ParameterStoreConfigDataSdkAdapter.load(any(), any())
            } throws software.amazon.awssdk.services.ssm.model.ParameterNotFoundException.builder().build()
            every {
                SecretsManagerConfigDataSdkAdapter.load(any(), any())
            } throws software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException.builder().build()

            ParameterStoreConfigDataLoader(mockk(relaxed = true), bootstrap)
                .load(context, parameter)
                .shouldBeNull()

            SecretsManagerConfigDataLoader(mockk(relaxed = true), bootstrap)
                .load(context, secret)
                .shouldBeNull()
        } finally {
            unmockkObject(ParameterStoreConfigDataSdkAdapter, SecretsManagerConfigDataSdkAdapter)
        }
    }

    private fun resource(location: String, disabled: Boolean = false): AwsConfigDataResource =
        AwsConfigDataResource.from(
            AwsConfigDataLocationParser().parse(ConfigDataLocation.of(location)),
            disabled = disabled,
        )

    private fun contextOf(bootstrap: ConfigurableBootstrapContext): ConfigDataLoaderContext =
        mockk {
            every { bootstrapContext } returns bootstrap
        }
}
