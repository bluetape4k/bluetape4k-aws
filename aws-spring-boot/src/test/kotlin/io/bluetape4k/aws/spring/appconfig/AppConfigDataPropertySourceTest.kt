package io.bluetape4k.aws.spring.appconfig

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.aws.spring.config.AwsConfigDataLocationParser
import io.bluetape4k.aws.spring.config.AwsConfigDataResource
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import org.springframework.boot.context.config.ConfigDataLocation
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.StandardEnvironment

class AppConfigDataPropertySourceTest {

    companion object: KLogging()

    @Test
    fun `property names and values switch atomically`() {
        val resource = AwsConfigDataResource.from(
            AwsConfigDataLocationParser().parse(ConfigDataLocation.of("aws-app-config:app#profile#env")),
        )
        val source = AppConfigDataPropertySource(
            name = "opaque-source",
            initialValues = mapOf("feature.old" to "old"),
            resource = resource,
            client = NoopSessionClient,
            request = AppConfigDataStartRequest("app", "profile", "env", 15),
            initialResponse = AppConfigDataResponse("token", 15, "text/plain", byteArrayOf()),
            format = AppConfigFormat.PROPERTIES,
            prefix = null,
            refreshInterval = null,
            requiredMinimumPollInterval = 15.seconds(),
        )
        log.debug { "source=$source" }

        source.getProperty("feature.old") shouldBeEqualTo "old"
        source.replace(mapOf("feature.new" to "new"))
        source.getPropertyNames().toSet() shouldBeEqualTo setOf("feature.new")
        source.getProperty("feature.old").shouldBeNull()
    }

    @Test
    fun `Environment sees the latest map while an existing bound object stays unchanged`() {
        val source = source()
        val environment = StandardEnvironment().apply {
            propertySources.addFirst(source)
        }
        log.debug { "environment=$environment" }

        val bound = Binder.get(environment)
            .bind("feature", FeatureProperties::class.java)
            .get()
        log.debug { "bound=$bound" }

        environment.getProperty("feature.value") shouldBeEqualTo "old"
        source.replace(mapOf("feature.value" to "new"))

        environment.getProperty("feature.value") shouldBeEqualTo "new"
        bound.value shouldBeEqualTo "old"
    }

    private fun source(): AppConfigDataPropertySource {
        val resource = AwsConfigDataResource.from(
            AwsConfigDataLocationParser().parse(ConfigDataLocation.of("aws-app-config:app#profile#env")),
        )
        return AppConfigDataPropertySource(
            name = "opaque-source",
            initialValues = mapOf("feature.value" to "old"),
            resource = resource,
            client = NoopSessionClient,
            request = AppConfigDataStartRequest("app", "profile", "env", 15),
            initialResponse = AppConfigDataResponse("token", 15, "text/plain", byteArrayOf()),
            format = AppConfigFormat.PROPERTIES,
            prefix = null,
            refreshInterval = null,
            requiredMinimumPollInterval = 15.seconds(),
        )
    }

    private data class FeatureProperties(
        var value: String = "",
    )

    private object NoopSessionClient: AppConfigDataSessionClient {
        override fun startConfigurationSession(request: AppConfigDataStartRequest): AppConfigDataSession =
            AppConfigDataSession("token")

        override fun getLatestConfiguration(configurationToken: String): AppConfigDataResponse =
            AppConfigDataResponse("token", 15, "text/plain", byteArrayOf())

        override fun close() = Unit
    }
}
