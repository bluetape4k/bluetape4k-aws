package io.bluetape4k.aws.ktor.imds

import aws.sdk.kotlin.runtime.config.imds.EndpointMode
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ImdsProfileResolverTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `system properties select profile and profile paths before environment values`() {
        val propertyConfig = write("property-config", """
            [profile application]
            ec2_metadata_service_endpoint = https://profile.example
            ec2_metadata_service_endpoint_mode = ipv6
            metadata_service_timeout = 1.25
        """.trimIndent())
        val environmentConfig = write("environment-config", """
            [profile staging]
            ec2_metadata_service_endpoint = https://wrong-profile.example
        """.trimIndent())
        val propertyCredentials = write("property-credentials", """
            [application]
            ec2_metadata_service_endpoint = https://credentials.example
        """.trimIndent())
        val environmentCredentials = write("environment-credentials", """
            [staging]
            ec2_metadata_service_endpoint = https://wrong-credentials.example
        """.trimIndent())

        val resolved = ImdsProfileResolver(
            systemProperties = mapOf(
                "aws.profile" to "application",
                "aws.configFile" to propertyConfig.toString(),
                "aws.sharedCredentialsFile" to propertyCredentials.toString(),
            ),
            environment = mapOf(
                "AWS_PROFILE" to "staging",
                "AWS_CONFIG_FILE" to environmentConfig.toString(),
                "AWS_SHARED_CREDENTIALS_FILE" to environmentCredentials.toString(),
            ),
            userHome = tempDir,
        ).resolve()

        resolved.profileName shouldBeEqualTo "application"
        resolved.endpoint.toString() shouldBeEqualTo "https://credentials.example"
        resolved.endpointMode.shouldBeNull()
        resolved.metadataServiceTimeout shouldBeEqualTo 1250.millis()
    }

    @Test
    fun `profile default section and last duplicate key follow Java profile precedence`() {
        val config = write("config", """
            [default]
            ec2_metadata_service_endpoint = https://old-default.example
            metadata_service_timeout = 1

            [profile default]
            ec2_metadata_service_endpoint = https://profile-default.example
            ec2_metadata_service_endpoint = https://last-profile-default.example
            metadata_service_timeout = 1.2345
        """.trimIndent())

        val resolved = ImdsProfileResolver(
            systemProperties = mapOf("aws.configFile" to config.toString()),
            environment = emptyMap(),
            userHome = tempDir,
        ).resolve()

        resolved.profileName shouldBeEqualTo "default"
        resolved.endpoint.toString() shouldBeEqualTo "https://last-profile-default.example"
        resolved.metadataServiceTimeout shouldBeEqualTo 1235.millis()
    }

    @Test
    fun `profile sections and values allow trailing comments`() {
        val config = write("config-with-comments", """
            [profile app] # selected profile
            ec2_metadata_service_endpoint = https://metadata.example ; endpoint note
            metadata_service_timeout = 1.25 # seconds
        """.trimIndent())

        val resolved = ImdsProfileResolver(
            systemProperties = mapOf("aws.profile" to "app", "aws.configFile" to config.toString()),
            environment = emptyMap(),
            userHome = tempDir,
        ).resolve()

        resolved.endpoint.toString() shouldBeEqualTo "https://metadata.example"
        resolved.metadataServiceTimeout shouldBeEqualTo 1250.millis()
    }

    @Test
    fun `system properties then environment then profile control scalar settings`() {
        val config = write("config", """
            [profile app]
            ec2_metadata_service_endpoint = https://profile.example
            ec2_metadata_service_endpoint_mode = ipv4
            metadata_service_timeout = 4
        """.trimIndent())

        val resolved = ImdsProfileResolver(
            systemProperties = mapOf(
                "aws.profile" to "app",
                "aws.configFile" to config.toString(),
                "aws.ec2MetadataServiceEndpoint" to "https://property.example",
                "aws.ec2MetadataServiceEndpointMode" to "ipv6",
                "aws.ec2MetadataServiceTimeout" to "2.5",
            ),
            environment = mapOf(
                "AWS_EC2_METADATA_SERVICE_ENDPOINT" to "https://environment.example",
                "AWS_EC2_METADATA_SERVICE_ENDPOINT_MODE" to "ipv4",
                "AWS_METADATA_SERVICE_TIMEOUT" to "3.5",
            ),
            userHome = tempDir,
        ).resolve()

        resolved.endpoint.toString() shouldBeEqualTo "https://property.example"
        resolved.endpointMode.shouldBeNull()
        resolved.metadataServiceTimeout shouldBeEqualTo 2500.millis()
    }

    @Test
    fun `profile endpoint mode resolves when no endpoint is selected`() {
        val config = write("mode-only-config", """
            [profile app]
            ec2_metadata_service_endpoint_mode = ipv6
        """.trimIndent())

        val resolved = ImdsProfileResolver(
            systemProperties = mapOf("aws.profile" to "app", "aws.configFile" to config.toString()),
            environment = emptyMap(),
            userHome = tempDir,
        ).resolve()

        resolved.endpoint.shouldBeNull()
        resolved.endpointMode shouldBeEqualTo EndpointMode.IPv6
    }

    @Test
    fun `selected endpoint shadows invalid lower priority endpoint mode`() {
        val config = write("config", """
            [profile app]
            ec2_metadata_service_endpoint = https://profile.example
            ec2_metadata_service_endpoint_mode = invalid
        """.trimIndent())

        val resolved = ImdsProfileResolver(
            systemProperties = mapOf(
                "aws.profile" to "app",
                "aws.configFile" to config.toString(),
                "aws.ec2MetadataServiceEndpoint" to "https://property.example",
            ),
            environment = mapOf("AWS_EC2_METADATA_SERVICE_ENDPOINT_MODE" to "invalid"),
            userHome = tempDir,
        ).resolve()

        resolved.endpoint.toString() shouldBeEqualTo "https://property.example"
        resolved.endpointMode.shouldBeNull()
    }

    @Test
    fun `environment profile paths and scalar values override home defaults`() {
        val homeConfig = tempDir.resolve("home/.aws/config")
        Files.createDirectories(homeConfig.parent)
        Files.writeString(homeConfig, """
            [profile env-app]
            ec2_metadata_service_endpoint = https://home.example
            ec2_metadata_service_endpoint_mode = ipv6
            metadata_service_timeout = 1
        """.trimIndent())
        val environmentConfig = write("environment-config", """
            [profile env-app]
            ec2_metadata_service_endpoint = https://environment-profile.example
            ec2_metadata_service_endpoint_mode = ipv4
            metadata_service_timeout = 2
        """.trimIndent())
        val environmentCredentials = write("environment-credentials", """
            [env-app]
            ec2_metadata_service_endpoint = https://environment-credentials.example
        """.trimIndent())

        val resolved = ImdsProfileResolver(
            systemProperties = emptyMap(),
            environment = mapOf(
                "AWS_PROFILE" to "env-app",
                "AWS_CONFIG_FILE" to environmentConfig.toString(),
                "AWS_SHARED_CREDENTIALS_FILE" to environmentCredentials.toString(),
                "AWS_METADATA_SERVICE_TIMEOUT" to "3.5",
            ),
            userHome = tempDir.resolve("home"),
        ).resolve()

        resolved.profileName shouldBeEqualTo "env-app"
        resolved.endpoint.toString() shouldBeEqualTo "https://environment-credentials.example"
        resolved.endpointMode.shouldBeNull()
        resolved.metadataServiceTimeout shouldBeEqualTo 3500.millis()
    }

    @Test
    fun `missing profile files leave scalar values unresolved`() {
        val resolved = ImdsProfileResolver(
            systemProperties = mapOf(
                "aws.configFile" to tempDir.resolve("missing-config").toString(),
                "aws.sharedCredentialsFile" to tempDir.resolve("missing-credentials").toString(),
            ),
            environment = emptyMap(),
            userHome = tempDir,
        ).resolve()

        resolved.profileName shouldBeEqualTo "default"
        resolved.endpoint.shouldBeNull()
        resolved.endpointMode.shouldBeNull()
        resolved.metadataServiceTimeout.shouldBeNull()
    }

    @Test
    fun `tilde paths expand against injected user home`() {
        val config = tempDir.resolve("aws/config")
        Files.createDirectories(config.parent)
        Files.writeString(config, """
            [profile app]
            metadata_service_timeout = 7
        """.trimIndent())

        val resolved = ImdsProfileResolver(
            systemProperties = mapOf("aws.profile" to "app", "aws.configFile" to "~/aws/config"),
            environment = emptyMap(),
            userHome = tempDir,
        ).resolve()

        resolved.metadataServiceTimeout shouldBeEqualTo 7.seconds()
    }

    @Test
    fun `nonnumeric timeout fails with profile key context`() {
        val config = write("config", """
            [profile app]
            metadata_service_timeout = fast
        """.trimIndent())

        val error = assertFailsWith<IllegalStateException> {
            ImdsProfileResolver(
                systemProperties = mapOf("aws.profile" to "app", "aws.configFile" to config.toString()),
                environment = emptyMap(),
                userHome = tempDir,
            ).resolve()
        }

        error.message shouldContain "metadata_service_timeout"
    }

    private fun write(name: String, content: String): Path =
        tempDir.resolve(name).also { Files.writeString(it, content) }
}
