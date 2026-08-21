package io.bluetape4k.aws.spring.config

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext
import org.springframework.boot.context.config.ConfigDataLocation
import org.springframework.boot.context.config.ConfigDataLocationResolverContext
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.boot.logging.DeferredLogFactory

class AwsConfigDataLocationResolverTest {

    @Test
    fun `disabled global configuration returns disabled resource before SDK guard`() {
        val bootstrap = mockk<ConfigurableBootstrapContext>(relaxed = true)
        val context = contextOf(
            bootstrap = bootstrap,
            "bluetape4k.aws.enabled" to false,
            "bluetape4k.aws.s3.config.enabled" to true,
        )

        val resource = io.bluetape4k.aws.spring.s3.S3ConfigDataLocationResolver(
            mockk(relaxed = true),
            context.getBinder(),
            bootstrap,
        ).resolve(context, ConfigDataLocation.of("aws-s3:/bucket/application.yml")).single()

        resource.isDisabled shouldBeEqualTo true
        verify(exactly = 0) { bootstrap.registerIfAbsent<Any>(any(), any()) }
    }

    @Test
    fun `resolver claims only its backend and does not add profile suffix`() {
        val bootstrap = mockk<ConfigurableBootstrapContext>(relaxed = true)
        every { bootstrap.isRegistered<Any>(any()) } returns true
        every { bootstrap.registerIfAbsent<Any>(any(), any()) } just runs
        val context = contextOf(bootstrap, "bluetape4k.aws.enabled" to false)
        val resolver = io.bluetape4k.aws.spring.parameterstore.ParameterStoreConfigDataLocationResolver(
            mockk(relaxed = true),
            context.getBinder(),
            bootstrap,
        )

        resolver.isResolvable(context, ConfigDataLocation.of("aws-parameterstore:/app")) shouldBeEqualTo true
        resolver.isResolvable(context, ConfigDataLocation.of("aws-s3:/bucket/key")) shouldBeEqualTo false
        resolver.resolveProfileSpecific(
            context,
            ConfigDataLocation.of("aws-parameterstore:/app"),
            mockk(relaxed = true),
        ).shouldBeEmpty()
    }

    @Test
    fun `active imports register one bootstrap client per backend`() {
        val bootstrap = mockk<ConfigurableBootstrapContext>(relaxed = true)
        every { bootstrap.isRegistered<Any>(any()) } returnsMany listOf(false, true)
        every { bootstrap.registerIfAbsent<Any>(any(), any()) } just runs
        every { bootstrap.addCloseListener(any()) } just runs
        val context = contextOf(bootstrap)
        val resolver = io.bluetape4k.aws.spring.secretsmanager.SecretsManagerConfigDataLocationResolver(
            mockk(relaxed = true),
            context.getBinder(),
            bootstrap,
        )

        resolver.resolve(context, ConfigDataLocation.of("aws-secretsmanager:prod"))
        resolver.resolve(context, ConfigDataLocation.of("aws-secretsmanager:prod-replica"))

        verify(exactly = 1) { bootstrap.registerIfAbsent<Any>(any(), any()) }
        verify(exactly = 1) { bootstrap.addCloseListener(any()) }
    }

    private fun contextOf(
        bootstrap: ConfigurableBootstrapContext,
        vararg properties: Pair<String, Any>,
    ): ConfigDataLocationResolverContext = mockk {
        every { getBinder() } returns Binder(MapConfigurationPropertySource(properties.toMap()))
        every { getBootstrapContext() } returns bootstrap
    }
}
