package io.bluetape4k.aws.spring.appconfig

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.aws.spring.AwsProperties
import io.bluetape4k.aws.spring.config.AwsConfigDataLocationParser
import io.bluetape4k.aws.spring.config.AwsConfigDataResource
import io.bluetape4k.aws.spring.config.AwsConfigDataSupport
import io.bluetape4k.aws.spring.config.AwsConfigDataLoadException
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext
import org.springframework.boot.context.config.ConfigDataLoaderContext
import org.springframework.core.env.MapPropertySource

class AppConfigDataLoaderTest {

    @Test
    fun `disabled resource returns empty ConfigData without client access`() {
        val bootstrap = mockk<ConfigurableBootstrapContext>(relaxed = true)
        val loader = AppConfigDataLoader(mockk(relaxed = true), bootstrap)
        val data = loader.load(contextOf(bootstrap), resource(bootstrap, disabled = true))

        (data!!.propertySources.single() as MapPropertySource).source shouldBeEqualTo emptyMap<String, Any>()
        verify(exactly = 0) { bootstrap.get<Any>(any()) }
    }

    @Test
    fun `initial response creates dynamic property source and preserves empty response`() {
        val bootstrap = mockk<ConfigurableBootstrapContext>(relaxed = true)
        val fake = FakeSessionClient()
        val initial = AppConfigDataInitialLoad(
            client = fake,
            request = AppConfigDataStartRequest("app", "profile", "env", 15),
            response = AppConfigDataResponse("token-2", 15, "text/plain", byteArrayOf()),
            values = emptyMap(),
            refreshInterval = null,
            requiredMinimumPollInterval = java.time.Duration.ofSeconds(15),
            format = AppConfigFormat.PROPERTIES,
            prefix = null,
        )
        mockkObject(AppConfigDataSdkAdapter)
        try {
            every { AppConfigDataSdkAdapter.initialLoad(bootstrap, any()) } returns initial
            val data = AppConfigDataLoader(mockk(relaxed = true), bootstrap)
                .load(contextOf(bootstrap), resource(bootstrap))

            data!!.propertySources.single().shouldBeInstanceOf<AppConfigDataPropertySource>()
            (data.propertySources.single() as AppConfigDataPropertySource).valuesSnapshot() shouldBeEqualTo emptyMap()
        } finally {
            unmockkObject(AppConfigDataSdkAdapter)
        }
    }

    @Test
    fun `optional and fail-fast policies sanitize initial failures`() {
        val bootstrap = mockk<ConfigurableBootstrapContext>(relaxed = true)
        mockkObject(AppConfigDataSdkAdapter)
        try {
            every { AppConfigDataSdkAdapter.initialLoad(any(), any()) } throws IllegalStateException("secret-body")

            val optional = io.bluetape4k.assertions.assertFailsWith<AwsConfigDataLoadException> {
                AppConfigDataLoader(mockk(relaxed = true), bootstrap)
                    .load(
                        contextOf(bootstrap),
                        resource(bootstrap, location = "optional:aws-app-config:app#profile#env"),
                    )
            }
            optional.toString() shouldNotContain "secret-body"

            val required = io.bluetape4k.assertions.assertFailsWith<AwsConfigDataLoadException> {
                AppConfigDataLoader(mockk(relaxed = true), bootstrap)
                    .load(contextOf(bootstrap), resource(bootstrap))
            }
            required.toString() shouldNotContain "secret-body"

            val nonFailFast = resource(
                bootstrap,
                properties = AppConfigProperties(failFast = false),
            )
            val empty = AppConfigDataLoader(mockk(relaxed = true), bootstrap).load(contextOf(bootstrap), nonFailFast)
            (empty!!.propertySources.single() as MapPropertySource).source shouldBeEqualTo emptyMap<String, Any>()
        } finally {
            unmockkObject(AppConfigDataSdkAdapter)
        }
    }

    private fun resource(
        bootstrap: ConfigurableBootstrapContext,
        location: String = "aws-app-config:app#profile#env",
        properties: AppConfigProperties = AppConfigProperties(),
        disabled: Boolean = false,
    ): AwsConfigDataResource = AwsConfigDataResource.from(
        location = AwsConfigDataLocationParser().parse(
            org.springframework.boot.context.config.ConfigDataLocation.of(location),
        ),
        boundProperties = AwsConfigDataSupport.ResolverConfiguration(AwsProperties(), properties, bootstrap),
        disabled = disabled,
    )

    private fun contextOf(bootstrap: ConfigurableBootstrapContext): ConfigDataLoaderContext = mockk {
        every { getBootstrapContext() } returns bootstrap
    }

    private class FakeSessionClient : AppConfigDataSessionClient {
        override fun startConfigurationSession(request: AppConfigDataStartRequest): AppConfigDataSession =
            AppConfigDataSession("token")

        override fun getLatestConfiguration(configurationToken: String): AppConfigDataResponse =
            AppConfigDataResponse("token-next", 15, "text/plain", byteArrayOf())

        override fun close() = Unit
    }
}
