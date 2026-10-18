package io.bluetape4k.aws.spring.appconfig

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.aws.spring.config.AwsConfigDataSource
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext
import org.springframework.boot.context.config.ConfigDataLocation
import org.springframework.boot.context.config.ConfigDataLocationResolverContext
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

class AppConfigDataLocationResolverTest {

    companion object: KLogging()

    private val bootstrap = mockk<ConfigurableBootstrapContext>(relaxed = true)

    @BeforeEach
    fun beforeEach() {
        clearMocks(bootstrap)
    }

    @Test
    fun `resolves custom separator and registers one guarded bootstrap client`() {
        every { bootstrap.isRegistered<Any>(any()) } returns false
        every { bootstrap.registerIfAbsent<Any>(any(), any()) } just runs
        every { bootstrap.addCloseListener(any()) } just runs

        val context = contextOf(bootstrap, "bluetape4k.aws.app-config.separator" to ";")
        val resolver = AppConfigDataLocationResolver(
            mockk(relaxed = true),
            context.binder,
            bootstrap,
        )

        resolver.isResolvable(context, ConfigDataLocation.of("aws-app-config:app;profile;env")).shouldBeTrue()
        val resource = resolver
            .resolve(context, ConfigDataLocation.of("aws-app-config:app;profile;env"))
            .single()
        log.debug { "resource=$resource" }

        resource.location.source shouldBeEqualTo AwsConfigDataSource.AppConfig(
            application = "app",
            profile = "profile",
            environment = "env",
            prefix = null,
            format = AppConfigFormat.AUTO,
        )

        verify(exactly = 1) { bootstrap.registerIfAbsent<Any>(any(), any()) }
        verify(exactly = 1) { bootstrap.addCloseListener(any()) }
    }

    @Test
    fun `disabled global switch creates a disabled resource without bootstrap registration`() {
        val context = contextOf(bootstrap, "bluetape4k.aws.enabled" to false)
        val resolver = AppConfigDataLocationResolver(mockk(relaxed = true), context.binder, bootstrap)

        val resource = resolver
            .resolve(context, ConfigDataLocation.of("aws-app-config:app#profile#env"))
            .single()

        log.debug { "resource=$resource" }
        resource.isDisabled.shouldBeTrue()

        verify(exactly = 0) { bootstrap.registerIfAbsent<Any>(any(), any()) }
    }

    @Test
    fun `does not append profile specific locations`() {
        val bootstrap = mockk<ConfigurableBootstrapContext>(relaxed = true)
        val context = contextOf(bootstrap, "bluetape4k.aws.enabled" to false)
        val resolver = AppConfigDataLocationResolver(mockk(relaxed = true), context.binder, bootstrap)

        resolver.resolveProfileSpecific(
            context,
            ConfigDataLocation.of("aws-app-config:app#profile#env"),
            mockk(relaxed = true),
        ).shouldBeEmpty()
    }

    private fun contextOf(
        bootstrap: ConfigurableBootstrapContext,
        vararg properties: Pair<String, Any>,
    ): ConfigDataLocationResolverContext = mockk {
        every { binder } returns Binder(MapConfigurationPropertySource(properties.toMap()))
        every { bootstrapContext } returns bootstrap
    }
}
