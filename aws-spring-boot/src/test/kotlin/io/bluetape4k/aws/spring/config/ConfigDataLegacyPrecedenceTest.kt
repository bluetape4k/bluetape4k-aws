package io.bluetape4k.aws.spring.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.aws.spring.env.AwsLoadedPropertySource
import io.bluetape4k.aws.spring.env.addAwsPropertySource
import org.junit.jupiter.api.Test
import org.springframework.boot.context.config.ConfigDataLocation
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

class ConfigDataLegacyPrecedenceTest {

    @Test
    fun `legacy EPP source stays below command line value`() {
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(
            MapPropertySource("commandLineArgs", mapOf("app.value" to "command-line")),
        )
        environment.addAwsPropertySource(
            AwsLoadedPropertySource(
                name = "bluetape4k.aws.configdata.opaque",
                values = mapOf("app.value" to "legacy"),
                reload = { null },
            ),
            refreshInterval = null,
        )

        environment.getProperty("app.value") shouldBeEqualTo "command-line"
    }

    @Test
    fun `ConfigData source name is opaque when legacy and startup paths overlap`() {
        val resource = AwsConfigDataResource.from(
            AwsConfigDataLocationParser().parse(ConfigDataLocation.of("aws-s3:/secret-bucket/secret-key")),
        )

        resource.toString() shouldBeEqualTo resource.opaqueIdentity
    }
}
