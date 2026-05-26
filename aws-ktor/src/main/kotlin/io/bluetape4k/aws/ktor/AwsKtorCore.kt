package io.bluetape4k.aws.ktor

import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import io.bluetape4k.AbstractValueObject
import io.bluetape4k.ToStringBuilder
import io.ktor.client.HttpClientConfig
import io.ktor.http.Url
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.createApplicationPlugin
import io.ktor.util.AttributeKey
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClientBuilder
import java.net.URI
import java.time.Clock

/**
 * Application-level AWS defaults shared by bluetape4k Ktor integrations.
 *
 * ## Contract
 *
 * Plugin-specific settings always override these defaults. The defaults are
 * opt-in: applications install [AwsKtorCore] once and then service plugins can
 * inherit the region, endpoint override, credentials, clock, and customizers.
 *
 * Live collaborator properties are transient because credentials providers,
 * HTTP engines, and customizer lambdas are runtime objects, not durable state.
 */
class AwsKtorDefaults(
    val region: String? = null,
    endpointOverride: Url? = null,
    @Transient val javaCredentialsProvider: AwsCredentialsProvider? = null,
    @Transient val kotlinCredentialsProvider: CredentialsProvider? = null,
    @Transient val signingClock: Clock? = null,
    @Transient val kotlinHttpClient: HttpClientEngine? = null,
    httpClientCustomizers: List<AwsKtorHttpClientCustomizer> = emptyList(),
    sqsAsyncClientCustomizers: List<AwsKtorSqsAsyncClientCustomizer> = emptyList(),
    dynamoDbClientCustomizers: List<AwsKtorDynamoDbClientCustomizer> = emptyList(),
): AbstractValueObject() {

    private val endpointOverrideValue: String? = endpointOverride?.toString()

    @Transient
    private val httpClientCustomizersValue: List<AwsKtorHttpClientCustomizer>? = httpClientCustomizers

    @Transient
    private val sqsAsyncClientCustomizersValue: List<AwsKtorSqsAsyncClientCustomizer>? = sqsAsyncClientCustomizers

    @Transient
    private val dynamoDbClientCustomizersValue: List<AwsKtorDynamoDbClientCustomizer>? = dynamoDbClientCustomizers

    init {
        require(endpointOverrideValue == null || !region.isNullOrBlank()) {
            "region is required when endpointOverride is configured."
        }
    }

    /** Shared endpoint override, commonly LocalStack in tests and local development. */
    val endpointOverride: Url?
        get() = endpointOverrideValue?.let(::Url)

    /** Shared customizers for plugin-created Ktor HTTP clients. */
    val httpClientCustomizers: List<AwsKtorHttpClientCustomizer>
        get() = httpClientCustomizersValue ?: emptyList()

    /** Shared customizers for plugin-created AWS SDK Java v2 SQS async clients. */
    val sqsAsyncClientCustomizers: List<AwsKtorSqsAsyncClientCustomizer>
        get() = sqsAsyncClientCustomizersValue ?: emptyList()

    /** Shared customizers for plugin-created AWS Kotlin SDK DynamoDB clients. */
    val dynamoDbClientCustomizers: List<AwsKtorDynamoDbClientCustomizer>
        get() = dynamoDbClientCustomizersValue ?: emptyList()

    internal val javaEndpointOverride: URI?
        get() = endpointOverrideValue?.let(URI::create)

    internal val effectiveRegion: Region?
        get() = region?.takeIf { it.isNotBlank() }?.let(Region::of)

    override fun equalProperties(other: Any): Boolean =
        other is AwsKtorDefaults &&
            region == other.region &&
            endpointOverrideValue == other.endpointOverrideValue &&
            javaCredentialsProvider == other.javaCredentialsProvider &&
            kotlinCredentialsProvider == other.kotlinCredentialsProvider &&
            signingClock == other.signingClock &&
            kotlinHttpClient == other.kotlinHttpClient &&
            httpClientCustomizers == other.httpClientCustomizers &&
            sqsAsyncClientCustomizers == other.sqsAsyncClientCustomizers &&
            dynamoDbClientCustomizers == other.dynamoDbClientCustomizers

    override fun hashCode(): Int {
        var result = region?.hashCode() ?: 0
        result = 31 * result + (endpointOverrideValue?.hashCode() ?: 0)
        result = 31 * result + (javaCredentialsProvider?.hashCode() ?: 0)
        result = 31 * result + (kotlinCredentialsProvider?.hashCode() ?: 0)
        result = 31 * result + (signingClock?.hashCode() ?: 0)
        result = 31 * result + (kotlinHttpClient?.hashCode() ?: 0)
        result = 31 * result + httpClientCustomizers.hashCode()
        result = 31 * result + sqsAsyncClientCustomizers.hashCode()
        result = 31 * result + dynamoDbClientCustomizers.hashCode()
        return result
    }

    override fun buildStringHelper(): ToStringBuilder =
        super.buildStringHelper()
            .add("region", region)
            .add("endpointOverride", endpointOverrideValue)
            .add("javaCredentialsProvider", javaCredentialsProvider)
            .add("kotlinCredentialsProvider", kotlinCredentialsProvider)
            .add("signingClock", signingClock)
            .add("kotlinHttpClient", kotlinHttpClient)
            .add("httpClientCustomizers", httpClientCustomizers)
            .add("sqsAsyncClientCustomizers", sqsAsyncClientCustomizers)
            .add("dynamoDbClientCustomizers", dynamoDbClientCustomizers)

    companion object {
        private const val serialVersionUID: Long = -6925410098353228441L
    }
}

/**
 * Ktor application plugin that stores shared AWS defaults in application attributes.
 */
val AwsKtorCore: ApplicationPlugin<AwsKtorCoreConfig> = createApplicationPlugin(
    name = "AwsKtorCore",
    createConfiguration = ::AwsKtorCoreConfig,
) {
    application.attributes.put(AwsKtorDefaultsKey, pluginConfig.toDefaults())
}

/**
 * Application attribute key for [AwsKtorDefaults].
 */
val AwsKtorDefaultsKey: AttributeKey<AwsKtorDefaults> = AttributeKey("AwsKtorDefaults")

/**
 * Returns installed shared AWS defaults, or an empty defaults value when [AwsKtorCore] is absent.
 */
fun Application.awsKtorDefaults(): AwsKtorDefaults =
    attributes.getOrNull(AwsKtorDefaultsKey) ?: AwsKtorDefaults()

/**
 * Mutable configuration for [AwsKtorCore].
 */
class AwsKtorCoreConfig {

    /** Shared AWS region inherited by service plugins unless they set their own region. */
    var region: String? = null

    /** Shared endpoint override, commonly LocalStack in tests and local development. */
    var endpointOverride: Url? = null

    /** Shared AWS SDK Java v2 credentials provider. */
    var javaCredentialsProvider: AwsCredentialsProvider? = null

    /** Shared AWS SDK for Kotlin credentials provider. */
    var kotlinCredentialsProvider: CredentialsProvider? = null

    /** Shared SigV4 signing clock for deterministic tests. */
    var signingClock: Clock? = null

    /** Shared AWS SDK for Kotlin HTTP engine. */
    var kotlinHttpClient: HttpClientEngine? = null

    private val httpClientCustomizers = mutableListOf<AwsKtorHttpClientCustomizer>()
    private val sqsAsyncClientCustomizers = mutableListOf<AwsKtorSqsAsyncClientCustomizer>()
    private val dynamoDbClientCustomizers = mutableListOf<AwsKtorDynamoDbClientCustomizer>()

    /**
     * Adds a global Ktor [io.ktor.client.HttpClient] customizer for plugin-created clients.
     */
    fun httpClient(customizer: AwsKtorHttpClientCustomizer) {
        httpClientCustomizers += customizer
    }

    /**
     * Adds a global SQS async client builder customizer.
     */
    fun sqsAsyncClient(customizer: AwsKtorSqsAsyncClientCustomizer) {
        sqsAsyncClientCustomizers += customizer
    }

    /**
     * Adds a global AWS Kotlin SDK DynamoDB client builder customizer.
     */
    fun dynamoDbClient(customizer: AwsKtorDynamoDbClientCustomizer) {
        dynamoDbClientCustomizers += customizer
    }

    internal fun toDefaults(): AwsKtorDefaults =
        AwsKtorDefaults(
            region = region,
            endpointOverride = endpointOverride,
            javaCredentialsProvider = javaCredentialsProvider,
            kotlinCredentialsProvider = kotlinCredentialsProvider,
            signingClock = signingClock,
            kotlinHttpClient = kotlinHttpClient,
            httpClientCustomizers = httpClientCustomizers.toList(),
            sqsAsyncClientCustomizers = sqsAsyncClientCustomizers.toList(),
            dynamoDbClientCustomizers = dynamoDbClientCustomizers.toList(),
        )
}

/**
 * Customizes plugin-created Ktor HTTP clients before they are built.
 */
fun interface AwsKtorHttpClientCustomizer {
    fun customize(config: HttpClientConfig<*>)
}

/**
 * Customizes plugin-created AWS SDK Java v2 SQS async client builders.
 */
fun interface AwsKtorSqsAsyncClientCustomizer {
    fun customize(builder: SqsAsyncClientBuilder)
}

/**
 * Customizes plugin-created AWS Kotlin SDK DynamoDB client builders.
 */
fun interface AwsKtorDynamoDbClientCustomizer {
    fun customize(builder: aws.sdk.kotlin.services.dynamodb.DynamoDbClient.Config.Builder)
}
