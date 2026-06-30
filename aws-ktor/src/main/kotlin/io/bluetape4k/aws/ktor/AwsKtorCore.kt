package io.bluetape4k.aws.ktor

import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import io.bluetape4k.AbstractValueObject
import io.bluetape4k.ToStringBuilder
import io.bluetape4k.ktor.core.Bluetape4kKtorCoreConfig
import io.bluetape4k.ktor.core.installBluetape4kKtorCore
import io.bluetape4k.support.hashOf
import io.ktor.client.HttpClientConfig
import io.ktor.http.Url
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.createApplicationPlugin
import io.ktor.util.AttributeKey
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClientBuilder
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsAsyncClientBuilder
import software.amazon.awssdk.services.s3control.S3ControlAsyncClientBuilder
import software.amazon.awssdk.services.s3vectors.S3VectorsAsyncClientBuilder
import software.amazon.awssdk.services.sesv2.SesV2AsyncClientBuilder
import software.amazon.awssdk.services.sns.SnsAsyncClientBuilder
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
    cloudWatchAsyncClientCustomizers: List<AwsKtorCloudWatchAsyncClientCustomizer> = emptyList(),
    cloudWatchLogsAsyncClientCustomizers: List<AwsKtorCloudWatchLogsAsyncClientCustomizer> = emptyList(),
    s3ControlAsyncClientCustomizers: List<AwsKtorS3ControlAsyncClientCustomizer> = emptyList(),
    s3VectorsAsyncClientCustomizers: List<AwsKtorS3VectorsAsyncClientCustomizer> = emptyList(),
    sesV2AsyncClientCustomizers: List<AwsKtorSesV2AsyncClientCustomizer> = emptyList(),
    snsAsyncClientCustomizers: List<AwsKtorSnsAsyncClientCustomizer> = emptyList(),
    dynamoDbClientCustomizers: List<AwsKtorDynamoDbClientCustomizer> = emptyList(),
): AbstractValueObject() {

    private val endpointOverrideValue: String? = endpointOverride?.toString()

    @Transient
    private val httpClientCustomizersValue: List<AwsKtorHttpClientCustomizer>? = httpClientCustomizers

    @Transient
    private val sqsAsyncClientCustomizersValue: List<AwsKtorSqsAsyncClientCustomizer>? = sqsAsyncClientCustomizers

    @Transient
    private val cloudWatchAsyncClientCustomizersValue: List<AwsKtorCloudWatchAsyncClientCustomizer>? =
        cloudWatchAsyncClientCustomizers

    @Transient
    private val cloudWatchLogsAsyncClientCustomizersValue: List<AwsKtorCloudWatchLogsAsyncClientCustomizer>? =
        cloudWatchLogsAsyncClientCustomizers

    @Transient
    private val s3ControlAsyncClientCustomizersValue: List<AwsKtorS3ControlAsyncClientCustomizer>? =
        s3ControlAsyncClientCustomizers

    @Transient
    private val s3VectorsAsyncClientCustomizersValue: List<AwsKtorS3VectorsAsyncClientCustomizer>? =
        s3VectorsAsyncClientCustomizers

    @Transient
    private val sesV2AsyncClientCustomizersValue: List<AwsKtorSesV2AsyncClientCustomizer>? =
        sesV2AsyncClientCustomizers

    @Transient
    private val snsAsyncClientCustomizersValue: List<AwsKtorSnsAsyncClientCustomizer>? =
        snsAsyncClientCustomizers

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

    /** Shared customizers for plugin-created AWS SDK Java v2 CloudWatch async clients. */
    val cloudWatchAsyncClientCustomizers: List<AwsKtorCloudWatchAsyncClientCustomizer>
        get() = cloudWatchAsyncClientCustomizersValue ?: emptyList()

    /** Shared customizers for plugin-created AWS SDK Java v2 CloudWatch Logs async clients. */
    val cloudWatchLogsAsyncClientCustomizers: List<AwsKtorCloudWatchLogsAsyncClientCustomizer>
        get() = cloudWatchLogsAsyncClientCustomizersValue ?: emptyList()

    /** Shared customizers for plugin-created AWS SDK Java v2 S3 Control async clients. */
    val s3ControlAsyncClientCustomizers: List<AwsKtorS3ControlAsyncClientCustomizer>
        get() = s3ControlAsyncClientCustomizersValue ?: emptyList()

    /** Shared customizers for plugin-created AWS SDK Java v2 S3 Vectors async clients. */
    val s3VectorsAsyncClientCustomizers: List<AwsKtorS3VectorsAsyncClientCustomizer>
        get() = s3VectorsAsyncClientCustomizersValue ?: emptyList()

    /** Shared customizers for plugin-created AWS SDK Java v2 SES v2 async clients. */
    val sesV2AsyncClientCustomizers: List<AwsKtorSesV2AsyncClientCustomizer>
        get() = sesV2AsyncClientCustomizersValue ?: emptyList()

    /** Shared customizers for plugin-created AWS SDK Java v2 SNS async clients. */
    val snsAsyncClientCustomizers: List<AwsKtorSnsAsyncClientCustomizer>
        get() = snsAsyncClientCustomizersValue ?: emptyList()

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
                cloudWatchAsyncClientCustomizers == other.cloudWatchAsyncClientCustomizers &&
                cloudWatchLogsAsyncClientCustomizers == other.cloudWatchLogsAsyncClientCustomizers &&
                s3ControlAsyncClientCustomizers == other.s3ControlAsyncClientCustomizers &&
                s3VectorsAsyncClientCustomizers == other.s3VectorsAsyncClientCustomizers &&
                sesV2AsyncClientCustomizers == other.sesV2AsyncClientCustomizers &&
                snsAsyncClientCustomizers == other.snsAsyncClientCustomizers &&
                dynamoDbClientCustomizers == other.dynamoDbClientCustomizers

    override fun equals(other: Any?): Boolean = super.equals(other)

    override fun hashCode(): Int {
        return hashOf(
            region,
            endpointOverrideValue,
            javaCredentialsProvider,
            kotlinCredentialsProvider,
            signingClock,
            kotlinHttpClient,
            httpClientCustomizers,
            sqsAsyncClientCustomizers,
            cloudWatchAsyncClientCustomizers,
            cloudWatchLogsAsyncClientCustomizers,
            s3ControlAsyncClientCustomizers,
            s3VectorsAsyncClientCustomizers,
            sesV2AsyncClientCustomizers,
            snsAsyncClientCustomizers,
            dynamoDbClientCustomizers,
        )
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
            .add("cloudWatchAsyncClientCustomizers", cloudWatchAsyncClientCustomizers)
            .add("cloudWatchLogsAsyncClientCustomizers", cloudWatchLogsAsyncClientCustomizers)
            .add("s3ControlAsyncClientCustomizers", s3ControlAsyncClientCustomizers)
            .add("s3VectorsAsyncClientCustomizers", s3VectorsAsyncClientCustomizers)
            .add("sesV2AsyncClientCustomizers", sesV2AsyncClientCustomizers)
            .add("snsAsyncClientCustomizers", snsAsyncClientCustomizers)
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
    pluginConfig.ktorCoreConfig?.let { config ->
        application.installBluetape4kKtorCore(config)
    }
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
    private val cloudWatchAsyncClientCustomizers = mutableListOf<AwsKtorCloudWatchAsyncClientCustomizer>()
    private val cloudWatchLogsAsyncClientCustomizers = mutableListOf<AwsKtorCloudWatchLogsAsyncClientCustomizer>()
    private val s3ControlAsyncClientCustomizers = mutableListOf<AwsKtorS3ControlAsyncClientCustomizer>()
    private val s3VectorsAsyncClientCustomizers = mutableListOf<AwsKtorS3VectorsAsyncClientCustomizer>()
    private val sesV2AsyncClientCustomizers = mutableListOf<AwsKtorSesV2AsyncClientCustomizer>()
    private val snsAsyncClientCustomizers = mutableListOf<AwsKtorSnsAsyncClientCustomizer>()
    private val dynamoDbClientCustomizers = mutableListOf<AwsKtorDynamoDbClientCustomizer>()

    internal var ktorCoreConfig: Bluetape4kKtorCoreConfig? = null
        private set

    /**
     * Installs the shared bluetape4k Ktor baseline together with [AwsKtorCore].
     *
     * The baseline remains opt-in so existing AWS-only applications do not get
     * content negotiation, status pages, or health routes unless they request
     * the shared bluetape4k Ktor server defaults explicitly.
     */
    fun ktorCore(config: Bluetape4kKtorCoreConfig = Bluetape4kKtorCoreConfig()) {
        ktorCoreConfig = config
    }

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
     * Adds a global CloudWatch async client builder customizer.
     */
    fun cloudWatchAsyncClient(customizer: AwsKtorCloudWatchAsyncClientCustomizer) {
        cloudWatchAsyncClientCustomizers += customizer
    }

    /**
     * Adds a global CloudWatch Logs async client builder customizer.
     */
    fun cloudWatchLogsAsyncClient(customizer: AwsKtorCloudWatchLogsAsyncClientCustomizer) {
        cloudWatchLogsAsyncClientCustomizers += customizer
    }

    /**
     * Adds a global S3 Control async client builder customizer.
     */
    fun s3ControlAsyncClient(customizer: AwsKtorS3ControlAsyncClientCustomizer) {
        s3ControlAsyncClientCustomizers += customizer
    }

    /**
     * Adds a global S3 Vectors async client builder customizer.
     */
    fun s3VectorsAsyncClient(customizer: AwsKtorS3VectorsAsyncClientCustomizer) {
        s3VectorsAsyncClientCustomizers += customizer
    }

    /**
     * Adds a global SES v2 async client builder customizer.
     */
    fun sesV2AsyncClient(customizer: AwsKtorSesV2AsyncClientCustomizer) {
        sesV2AsyncClientCustomizers += customizer
    }

    /**
     * Adds a global SNS async client builder customizer.
     */
    fun snsAsyncClient(customizer: AwsKtorSnsAsyncClientCustomizer) {
        snsAsyncClientCustomizers += customizer
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
            cloudWatchAsyncClientCustomizers = cloudWatchAsyncClientCustomizers.toList(),
            cloudWatchLogsAsyncClientCustomizers = cloudWatchLogsAsyncClientCustomizers.toList(),
            s3ControlAsyncClientCustomizers = s3ControlAsyncClientCustomizers.toList(),
            s3VectorsAsyncClientCustomizers = s3VectorsAsyncClientCustomizers.toList(),
            sesV2AsyncClientCustomizers = sesV2AsyncClientCustomizers.toList(),
            snsAsyncClientCustomizers = snsAsyncClientCustomizers.toList(),
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
 * Customizes plugin-created AWS SDK Java v2 CloudWatch async client builders.
 */
fun interface AwsKtorCloudWatchAsyncClientCustomizer {
    fun customize(builder: CloudWatchAsyncClientBuilder)
}

/**
 * Customizes plugin-created AWS SDK Java v2 CloudWatch Logs async client builders.
 */
fun interface AwsKtorCloudWatchLogsAsyncClientCustomizer {
    fun customize(builder: CloudWatchLogsAsyncClientBuilder)
}

/**
 * Customizes plugin-created AWS SDK Java v2 S3 Control async client builders.
 */
fun interface AwsKtorS3ControlAsyncClientCustomizer {
    fun customize(builder: S3ControlAsyncClientBuilder)
}

/**
 * Customizes plugin-created AWS SDK Java v2 S3 Vectors async client builders.
 */
fun interface AwsKtorS3VectorsAsyncClientCustomizer {
    fun customize(builder: S3VectorsAsyncClientBuilder)
}

/**
 * Customizes plugin-created AWS SDK Java v2 SES v2 async client builders.
 */
fun interface AwsKtorSesV2AsyncClientCustomizer {
    fun customize(builder: SesV2AsyncClientBuilder)
}

/**
 * Customizes plugin-created AWS SDK Java v2 SNS async client builders.
 */
fun interface AwsKtorSnsAsyncClientCustomizer {
    fun customize(builder: SnsAsyncClientBuilder)
}

/**
 * Customizes plugin-created AWS Kotlin SDK DynamoDB client builders.
 */
fun interface AwsKtorDynamoDbClientCustomizer {
    fun customize(builder: aws.sdk.kotlin.services.dynamodb.DynamoDbClient.Config.Builder)
}
