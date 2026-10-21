package io.bluetape4k.aws.ktor.dynamodb

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.endpoints.DynamoDbEndpointDiscoverer
import aws.sdk.kotlin.services.dynamodb.endpoints.DynamoDbEndpointParameters
import aws.sdk.kotlin.services.dynamodb.endpoints.DynamoDbEndpointProvider
import aws.sdk.kotlin.services.dynamodb.model.AttributeDefinition
import aws.sdk.kotlin.services.dynamodb.model.CreateTableRequest
import aws.sdk.kotlin.services.dynamodb.model.ListTablesRequest
import aws.sdk.kotlin.services.dynamodb.model.KeySchemaElement
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.client.endpoints.Endpoint
import aws.smithy.kotlin.runtime.auth.awscredentials.CloseableCredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.CloseableHttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.aws.kotlin.dynamodb.model.partitionKeyOf
import io.bluetape4k.aws.kotlin.dynamodb.model.stringAttrDefinitionOf
import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorDynamoDbClientCustomizer
import io.bluetape4k.aws.ktor.AwsKotlinEndpointPolicyInterceptor
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.ktor.core.ApplicationResourceRegistry
import io.bluetape4k.ktor.core.ApplicationResourceRegistryState
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.Serializable
import kotlin.Function1
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamoDbKtorRuntimeConfigTest {

    @Test
    fun `runtime configuration does not claim Java serialization support`() {
        Serializable::class.java.isAssignableFrom(DynamoDbKtorRuntimeConfig::class.java).shouldBeFalse()
        Serializable::class.java.isAssignableFrom(DynamoDbKtorTableDefinition::class.java).shouldBeFalse()
    }

    @Test
    fun `runtime configuration does not generate ownership copies or expose fields`() {
        val client = mockk<DynamoDbClient>(relaxed = true)
        val config = DynamoDbKtorRuntimeConfig(
            dynamoDbClient = client,
            ownsClient = true,
        )

        DynamoDbKtorRuntimeConfig::class.java.methods.any { it.name == "copy" }.shouldBeFalse()
        config.toString() shouldNotContain "dynamoDbClient="
        config.toString() shouldNotContain "ownsClient="
    }

    @Test
    fun `requires region when client is not injected`() {
        assertFailsWith<IllegalArgumentException> {
            DynamoDbKtorPluginConfig().toRuntimeConfig()
        }
    }

    @Test
    fun `validates table definition`() {
        assertFailsWith<IllegalArgumentException> {
            DynamoDbKtorTableDefinition(
                tableName = " ",
                keySchema = keySchema,
                attributeDefinitions = attributeDefinitions,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            DynamoDbKtorTableDefinition(
                tableName = "orders",
                keySchema = emptyList(),
                attributeDefinitions = attributeDefinitions,
            )
        }
    }

    @Test
    fun `table definition toString does not invoke custom builder toString`() {
        var builderToStringCalls = 0
        val createTable = object: Function1<CreateTableRequest.Builder, Unit> {
            override fun invoke(p1: CreateTableRequest.Builder) = Unit

            override fun toString(): String {
                builderToStringCalls++
                return "create-table-secret"
            }
        }
        val definition = DynamoDbKtorTableDefinition(
            tableName = "orders",
            keySchema = keySchema,
            attributeDefinitions = attributeDefinitions,
            createTable = createTable,
        )

        val rendered = definition.toString()

        rendered shouldNotContain "create-table-secret"
        builderToStringCalls shouldBeEqualTo 0
    }

    @Test
    fun `table definition does not expose data class ownership methods`() {
        val methods = DynamoDbKtorTableDefinition::class.java.methods

        methods.any { it.name == "copy" }.shouldBeFalse()
        methods.any { it.name.startsWith("component") }.shouldBeFalse()
    }

    @Test
    fun `does not close injected client`() = runSuspendIO {
        val client = mockk<DynamoDbClient>(relaxed = true)
        every { client.close() } returns Unit

        val runtime = DynamoDbKtorRuntime(
            DynamoDbKtorRuntimeConfig(
                dynamoDbClient = client,
                ownsClient = false,
                closeTimeout = 1.seconds,
            )
        )

        runtime.stop()

        verify(exactly = 0) { client.close() }
    }

    @Test
    fun `closes plugin owned client only once`() = runSuspendIO {
        val client = mockk<DynamoDbClient>(relaxed = true)
        every { client.close() } returns Unit

        val runtime = DynamoDbKtorRuntime(
            DynamoDbKtorRuntimeConfig(
                dynamoDbClient = client,
                ownsClient = true,
                closeTimeout = 1.seconds,
            )
        )

        runtime.stop()
        runtime.stop()

        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `application resource lifecycle keeps injected client caller owned`() {
        val client = mockk<DynamoDbClient>(relaxed = true)
        every { client.close() } returns Unit
        val runtime = DynamoDbKtorRuntime(
            DynamoDbKtorRuntimeConfig(
                dynamoDbClient = client,
                ownsClient = false,
                closeTimeout = 1.seconds,
            )
        )
        val registry = ApplicationResourceRegistry()

        runtime.registerApplicationResources(registry)
        registry.close()

        verify(exactly = 0) { client.close() }
        registry.closeReport.attempted shouldBeEqualTo 0
    }

    @Test
    fun `application resource lifecycle closes plugin owned client`() = runSuspendIO {
        val client = mockk<DynamoDbClient>(relaxed = true)
        every { client.close() } returns Unit
        val runtime = DynamoDbKtorRuntime(
            DynamoDbKtorRuntimeConfig(
                dynamoDbClient = client,
                ownsClient = true,
                closeTimeout = 1.seconds,
            )
        )
        val registry = ApplicationResourceRegistry()

        runtime.registerApplicationResources(registry)
        registry.close()

        verify(exactly = 1) { client.close() }
        registry.closeReport.state shouldBeEqualTo ApplicationResourceRegistryState.CLOSED
        registry.closeReport.closed shouldBeEqualTo 1
    }

    @Test
    fun `application resource lifecycle does not double close after direct stop`() = runSuspendIO {
        val client = mockk<DynamoDbClient>(relaxed = true)
        every { client.close() } returns Unit
        val runtime = DynamoDbKtorRuntime(
            DynamoDbKtorRuntimeConfig(
                dynamoDbClient = client,
                ownsClient = true,
                closeTimeout = 1.seconds,
            )
        )
        val registry = ApplicationResourceRegistry()

        runtime.registerApplicationResources(registry)
        runtime.stop()
        registry.close()

        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `shared AWS defaults create plugin owned DynamoDB client`() {
        val runtimeConfig = DynamoDbKtorPluginConfig().toRuntimeConfig(
            AwsKtorDefaults(region = "ap-northeast-2")
        )

        runtimeConfig.ownsClient shouldBeEqualTo true

        runtimeConfig.dynamoDbClient.close()
    }

    @Test
    fun `plugin client close preserves caller owned credentials provider and HTTP engine`() = runSuspendIO {
        val credentialsProvider = mockk<CloseableCredentialsProvider>(relaxed = true)
        val httpClient = mockk<CloseableHttpClientEngine>(relaxed = true)
        val runtimeConfig = DynamoDbKtorPluginConfig().toRuntimeConfig(
            AwsKtorDefaults(
                region = "ap-northeast-2",
                kotlinCredentialsProvider = credentialsProvider,
                kotlinHttpClient = httpClient,
            ),
        )

        runtimeConfig.dynamoDbClient.config.credentialsProvider shouldBeSameInstanceAs credentialsProvider
        runtimeConfig.dynamoDbClient.config.httpClient shouldBeSameInstanceAs httpClient

        DynamoDbKtorRuntime(runtimeConfig).stop()

        verify(exactly = 0) { credentialsProvider.close() }
        verify(exactly = 0) { httpClient.close() }
    }

    @Test
    fun `service DynamoDB customizer runs after shared customizer`() {
        val order = mutableListOf<String>()
        val runtimeConfig = DynamoDbKtorPluginConfig().apply {
            client { order += "service" }
        }.toRuntimeConfig(
            AwsKtorDefaults(
                region = "ap-northeast-2",
                dynamoDbClientCustomizers = listOf(AwsKtorDynamoDbClientCustomizer { order += "shared" }),
            )
        )

        order shouldBeEqualTo listOf("shared", "service")

        runtimeConfig.dynamoDbClient.close()
    }

    @Test
    fun `validates final endpoint after shared and service customizers before credentials`() {
        val credentialsProvider = mockk<CredentialsProvider>(relaxed = true)
        val defaults = AwsKtorDefaults(
            region = "ap-northeast-2",
            dynamoDbClientCustomizers = listOf(
                AwsKtorDynamoDbClientCustomizer { builder ->
                    builder.endpointUrl = aws.smithy.kotlin.runtime.net.url.Url.parse("https://service.example.com")
                },
            ),
        )

        val runtimeConfig = DynamoDbKtorPluginConfig()
            .apply {
                this.credentialsProvider = credentialsProvider
                client {
                    endpointUrl = Url.parse("http://169.254.169.254")
                }
            }
            .toRuntimeConfig(defaults)

        try {
            assertFailsWith<IllegalArgumentException> {
                runSuspendIO {
                    runtimeConfig.dynamoDbClient.listTables(ListTablesRequest {})
                }
            }
            coVerify(exactly = 0) { credentialsProvider.resolve() }
        } finally {
            runtimeConfig.dynamoDbClient.close()
        }
    }

    @Test
    fun `allows loopback HTTP endpoint only with shared opt in`() {
        val runtimeConfig = DynamoDbKtorPluginConfig()
            .apply {
                client {
                    endpointUrl = Url.parse("http://localhost:4566")
                }
            }
            .toRuntimeConfig(
                AwsKtorDefaults(
                    region = "ap-northeast-2",
                    allowInsecureLocalHttpEndpoint = true,
                ),
            )

        var resolvedEndpoint: Endpoint? = null
        runSuspendIO {
            resolvedEndpoint =
                runtimeConfig.dynamoDbClient.config.endpointProvider.resolveEndpoint(
                    DynamoDbEndpointParameters {
                        region = "ap-northeast-2"
                        endpoint = runtimeConfig.dynamoDbClient.config.endpointUrl?.toString()
                    },
                )
        }

        resolvedEndpoint.shouldNotBeNull().uri.toString() shouldBeEqualTo "http://localhost:4566"
        runtimeConfig.dynamoDbClient.close()
    }

    @Test
    fun `rejects service endpoint provider before resolving credentials`() {
        var serviceProviderCalls = 0
        val credentialsProvider = mockk<CredentialsProvider>(relaxed = true)
        assertFailsWith<IllegalArgumentException> {
            DynamoDbKtorPluginConfig().apply {
                this.credentialsProvider = credentialsProvider
                client {
                    endpointProvider = DynamoDbEndpointProvider {
                        serviceProviderCalls++
                        Endpoint(Url.parse("http://169.254.169.254/latest/meta-data"))
                    }
                }
            }.toRuntimeConfig(
                AwsKtorDefaults(region = "ap-northeast-2"),
            )
        }

        serviceProviderCalls shouldBeEqualTo 0
        coVerify(exactly = 0) { credentialsProvider.resolve() }
    }

    @Test
    fun `rejects shared endpoint provider before resolving credentials`() {
        var sharedProviderCalls = 0
        val credentialsProvider = mockk<CredentialsProvider>(relaxed = true)

        assertFailsWith<IllegalArgumentException> {
            DynamoDbKtorPluginConfig().apply {
                this.credentialsProvider = credentialsProvider
            }.toRuntimeConfig(
                AwsKtorDefaults(
                    region = "ap-northeast-2",
                    kotlinCredentialsProvider = credentialsProvider,
                    dynamoDbClientCustomizers = listOf(
                        AwsKtorDynamoDbClientCustomizer { builder ->
                            builder.endpointProvider = DynamoDbEndpointProvider {
                                sharedProviderCalls++
                                Endpoint(Url.parse("https://service.example.com"))
                            }
                        },
                    ),
                ),
            )
        }

        sharedProviderCalls shouldBeEqualTo 0
        coVerify(exactly = 0) { credentialsProvider.resolve() }
    }

    @Test
    fun `rejects DynamoDB endpoint discovery during client configuration`() {
        val discoverer = mockk<DynamoDbEndpointDiscoverer>(relaxed = true)
        val credentialsProvider = mockk<CredentialsProvider>(relaxed = true)

        assertFailsWith<IllegalArgumentException> {
            DynamoDbKtorPluginConfig().apply {
                this.credentialsProvider = credentialsProvider
                client { endpointDiscoverer = discoverer }
            }.toRuntimeConfig(AwsKtorDefaults(region = "ap-northeast-2"))
        }

        coVerify(exactly = 0) { credentialsProvider.resolve() }
    }

    @Test
    fun `rejects invalid configured endpoint before resolving credentials`() {
        val credentialsProvider = mockk<CredentialsProvider>(relaxed = true)
        val runtimeConfig = DynamoDbKtorPluginConfig().apply {
            region = "ap-northeast-2"
            endpointUrl = Url.parse("http://169.254.169.254/latest/meta-data")
            this.credentialsProvider = credentialsProvider
        }.toRuntimeConfig()

        try {
            assertFailsWith<IllegalArgumentException> {
                runSuspendIO {
                    runtimeConfig.dynamoDbClient.listTables(ListTablesRequest {})
                }
            }
            coVerify(exactly = 0) { credentialsProvider.resolve() }
        } finally {
            runtimeConfig.dynamoDbClient.close()
        }
    }

    private val keySchema: List<KeySchemaElement> = listOf(partitionKeyOf("id"))
    private val attributeDefinitions: List<AttributeDefinition> = listOf(stringAttrDefinitionOf("id"))
}
