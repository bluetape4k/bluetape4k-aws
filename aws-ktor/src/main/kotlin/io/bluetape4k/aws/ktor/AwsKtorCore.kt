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
import software.amazon.awssdk.services.eventbridge.EventBridgeAsyncClientBuilder
import software.amazon.awssdk.services.kinesis.KinesisAsyncClientBuilder
import software.amazon.awssdk.services.s3control.S3ControlAsyncClientBuilder
import software.amazon.awssdk.services.s3vectors.S3VectorsAsyncClientBuilder
import software.amazon.awssdk.services.sesv2.SesV2AsyncClientBuilder
import software.amazon.awssdk.services.sns.SnsAsyncClientBuilder
import software.amazon.awssdk.services.sqs.SqsAsyncClientBuilder
import software.amazon.awssdk.services.sts.StsAsyncClientBuilder
import java.net.URI
import java.time.Clock

/**
 * bluetape4k Ktor 통합이 공유하는 애플리케이션 수준 AWS 기본값입니다.
 *
 * ## 계약
 *
 * 플러그인별 설정은 항상 이 기본값보다 우선합니다. 기본값은 옵트인 방식입니다. 애플리케이션이
 * [AwsKtorCore]를 한 번 설치하면 서비스 플러그인이 리전, 엔드포인트 재정의, 자격 증명, 시계,
 * 사용자 정의 설정을 상속할 수 있습니다.
 *
 * 자격 증명 공급자, HTTP 엔진, 사용자 정의 람다는 영속 상태가 아닌 런타임 객체이므로
 * 실행 중인 협력 객체 속성은 transient입니다.
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
    eventBridgeAsyncClientCustomizers: List<AwsKtorEventBridgeAsyncClientCustomizer> = emptyList(),
    kinesisAsyncClientCustomizers: List<AwsKtorKinesisAsyncClientCustomizer> = emptyList(),
    s3ControlAsyncClientCustomizers: List<AwsKtorS3ControlAsyncClientCustomizer> = emptyList(),
    s3VectorsAsyncClientCustomizers: List<AwsKtorS3VectorsAsyncClientCustomizer> = emptyList(),
    sesV2AsyncClientCustomizers: List<AwsKtorSesV2AsyncClientCustomizer> = emptyList(),
    snsAsyncClientCustomizers: List<AwsKtorSnsAsyncClientCustomizer> = emptyList(),
    dynamoDbClientCustomizers: List<AwsKtorDynamoDbClientCustomizer> = emptyList(),
    stsAsyncClientCustomizers: List<AwsKtorStsAsyncClientCustomizer> = emptyList(),
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
    private val eventBridgeAsyncClientCustomizersValue: List<AwsKtorEventBridgeAsyncClientCustomizer>? =
        eventBridgeAsyncClientCustomizers

    @Transient
    private val kinesisAsyncClientCustomizersValue: List<AwsKtorKinesisAsyncClientCustomizer>? =
        kinesisAsyncClientCustomizers

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

    @Transient
    private val stsAsyncClientCustomizersValue: List<AwsKtorStsAsyncClientCustomizer>? = stsAsyncClientCustomizers

    init {
        require(endpointOverrideValue == null || !region.isNullOrBlank()) {
            "region is required when endpointOverride is configured."
        }
    }

    /** 테스트와 로컬 개발에서 주로 LocalStack을 지정하는 공유 엔드포인트 재정의입니다. */
    val endpointOverride: Url?
        get() = endpointOverrideValue?.let(::Url)

    /** 플러그인이 생성한 Ktor HTTP 클라이언트의 공유 사용자 정의 설정입니다. */
    val httpClientCustomizers: List<AwsKtorHttpClientCustomizer>
        get() = httpClientCustomizersValue ?: emptyList()

    /** 플러그인이 생성한 AWS SDK Java v2 SQS 비동기 클라이언트의 공유 사용자 정의 설정입니다. */
    val sqsAsyncClientCustomizers: List<AwsKtorSqsAsyncClientCustomizer>
        get() = sqsAsyncClientCustomizersValue ?: emptyList()

    /** 플러그인이 생성한 AWS SDK Java v2 CloudWatch 비동기 클라이언트의 공유 사용자 정의 설정입니다. */
    val cloudWatchAsyncClientCustomizers: List<AwsKtorCloudWatchAsyncClientCustomizer>
        get() = cloudWatchAsyncClientCustomizersValue ?: emptyList()

    /** 플러그인이 생성한 AWS SDK Java v2 CloudWatch Logs 비동기 클라이언트의 공유 사용자 정의 설정입니다. */
    val cloudWatchLogsAsyncClientCustomizers: List<AwsKtorCloudWatchLogsAsyncClientCustomizer>
        get() = cloudWatchLogsAsyncClientCustomizersValue ?: emptyList()

    /** 플러그인이 생성한 AWS SDK Java v2 EventBridge 비동기 클라이언트의 공유 사용자 정의 설정입니다. */
    val eventBridgeAsyncClientCustomizers: List<AwsKtorEventBridgeAsyncClientCustomizer>
        get() = eventBridgeAsyncClientCustomizersValue ?: emptyList()

    /** 플러그인이 생성한 AWS SDK Java v2 Kinesis 비동기 클라이언트의 공유 사용자 정의 설정입니다. */
    val kinesisAsyncClientCustomizers: List<AwsKtorKinesisAsyncClientCustomizer>
        get() = kinesisAsyncClientCustomizersValue ?: emptyList()

    /** 플러그인이 생성한 AWS SDK Java v2 S3 Control 비동기 클라이언트의 공유 사용자 정의 설정입니다. */
    val s3ControlAsyncClientCustomizers: List<AwsKtorS3ControlAsyncClientCustomizer>
        get() = s3ControlAsyncClientCustomizersValue ?: emptyList()

    /** 플러그인이 생성한 AWS SDK Java v2 S3 Vectors 비동기 클라이언트의 공유 사용자 정의 설정입니다. */
    val s3VectorsAsyncClientCustomizers: List<AwsKtorS3VectorsAsyncClientCustomizer>
        get() = s3VectorsAsyncClientCustomizersValue ?: emptyList()

    /** 플러그인이 생성한 AWS SDK Java v2 SES v2 비동기 클라이언트의 공유 사용자 정의 설정입니다. */
    val sesV2AsyncClientCustomizers: List<AwsKtorSesV2AsyncClientCustomizer>
        get() = sesV2AsyncClientCustomizersValue ?: emptyList()

    /** 플러그인이 생성한 AWS SDK Java v2 SNS 비동기 클라이언트의 공유 사용자 정의 설정입니다. */
    val snsAsyncClientCustomizers: List<AwsKtorSnsAsyncClientCustomizer>
        get() = snsAsyncClientCustomizersValue ?: emptyList()

    /** 플러그인이 생성한 AWS Kotlin SDK DynamoDB 클라이언트의 공유 사용자 정의 설정입니다. */
    val dynamoDbClientCustomizers: List<AwsKtorDynamoDbClientCustomizer>
        get() = dynamoDbClientCustomizersValue ?: emptyList()

    /** 플러그인이 생성한 AWS SDK Java v2 STS 비동기 클라이언트의 공유 사용자 정의 설정입니다. */
    val stsAsyncClientCustomizers: List<AwsKtorStsAsyncClientCustomizer>
        get() = stsAsyncClientCustomizersValue ?: emptyList()

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
                eventBridgeAsyncClientCustomizers == other.eventBridgeAsyncClientCustomizers &&
                kinesisAsyncClientCustomizers == other.kinesisAsyncClientCustomizers &&
                s3ControlAsyncClientCustomizers == other.s3ControlAsyncClientCustomizers &&
                s3VectorsAsyncClientCustomizers == other.s3VectorsAsyncClientCustomizers &&
                sesV2AsyncClientCustomizers == other.sesV2AsyncClientCustomizers &&
                snsAsyncClientCustomizers == other.snsAsyncClientCustomizers &&
                dynamoDbClientCustomizers == other.dynamoDbClientCustomizers &&
                stsAsyncClientCustomizers == other.stsAsyncClientCustomizers

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
            eventBridgeAsyncClientCustomizers,
            kinesisAsyncClientCustomizers,
            s3ControlAsyncClientCustomizers,
            s3VectorsAsyncClientCustomizers,
            sesV2AsyncClientCustomizers,
            snsAsyncClientCustomizers,
            dynamoDbClientCustomizers,
            stsAsyncClientCustomizers,
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
            .add("eventBridgeAsyncClientCustomizers", eventBridgeAsyncClientCustomizers)
            .add("kinesisAsyncClientCustomizers", kinesisAsyncClientCustomizers)
            .add("s3ControlAsyncClientCustomizers", s3ControlAsyncClientCustomizers)
            .add("s3VectorsAsyncClientCustomizers", s3VectorsAsyncClientCustomizers)
            .add("sesV2AsyncClientCustomizers", sesV2AsyncClientCustomizers)
            .add("snsAsyncClientCustomizers", snsAsyncClientCustomizers)
            .add("dynamoDbClientCustomizers", dynamoDbClientCustomizers)
            .add("stsAsyncClientCustomizers", stsAsyncClientCustomizers)

    companion object {
        private const val serialVersionUID: Long = -6925410098353228441L
    }
}

/**
 * 공유 AWS 기본값을 애플리케이션 속성에 저장하는 Ktor 애플리케이션 플러그인입니다.
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
 * [AwsKtorDefaults]용 애플리케이션 속성 키입니다.
 */
val AwsKtorDefaultsKey: AttributeKey<AwsKtorDefaults> = AttributeKey("AwsKtorDefaults")

/**
 * 설치된 공유 AWS 기본값을 반환합니다. [AwsKtorCore]가 없으면 빈 기본값을 반환합니다.
 */
fun Application.awsKtorDefaults(): AwsKtorDefaults =
    attributes.getOrNull(AwsKtorDefaultsKey) ?: AwsKtorDefaults()

/**
 * [AwsKtorCore]의 변경 가능한 구성입니다.
 */
class AwsKtorCoreConfig {

    /** 서비스 플러그인이 자체 리전을 지정하지 않을 때 상속하는 공유 AWS 리전입니다. */
    var region: String? = null

    /** 테스트와 로컬 개발에서 주로 LocalStack을 지정하는 공유 엔드포인트 재정의입니다. */
    var endpointOverride: Url? = null

    /** 공유 AWS SDK Java v2 자격 증명 공급자입니다. */
    var javaCredentialsProvider: AwsCredentialsProvider? = null

    /** 공유 AWS SDK for Kotlin 자격 증명 공급자입니다. */
    var kotlinCredentialsProvider: CredentialsProvider? = null

    /** 결정론적 테스트를 위한 공유 SigV4 서명 시계입니다. */
    var signingClock: Clock? = null

    /** 공유 AWS SDK for Kotlin HTTP 엔진입니다. */
    var kotlinHttpClient: HttpClientEngine? = null

    private val httpClientCustomizers = mutableListOf<AwsKtorHttpClientCustomizer>()
    private val sqsAsyncClientCustomizers = mutableListOf<AwsKtorSqsAsyncClientCustomizer>()
    private val cloudWatchAsyncClientCustomizers = mutableListOf<AwsKtorCloudWatchAsyncClientCustomizer>()
    private val cloudWatchLogsAsyncClientCustomizers = mutableListOf<AwsKtorCloudWatchLogsAsyncClientCustomizer>()
    private val eventBridgeAsyncClientCustomizers = mutableListOf<AwsKtorEventBridgeAsyncClientCustomizer>()
    private val kinesisAsyncClientCustomizers = mutableListOf<AwsKtorKinesisAsyncClientCustomizer>()
    private val s3ControlAsyncClientCustomizers = mutableListOf<AwsKtorS3ControlAsyncClientCustomizer>()
    private val s3VectorsAsyncClientCustomizers = mutableListOf<AwsKtorS3VectorsAsyncClientCustomizer>()
    private val sesV2AsyncClientCustomizers = mutableListOf<AwsKtorSesV2AsyncClientCustomizer>()
    private val snsAsyncClientCustomizers = mutableListOf<AwsKtorSnsAsyncClientCustomizer>()
    private val dynamoDbClientCustomizers = mutableListOf<AwsKtorDynamoDbClientCustomizer>()
    private val stsAsyncClientCustomizers = mutableListOf<AwsKtorStsAsyncClientCustomizer>()

    internal var ktorCoreConfig: Bluetape4kKtorCoreConfig? = null
        private set

    /**
     * [AwsKtorCore]와 함께 공유 bluetape4k Ktor 기준 구성을 설치합니다.
     *
     * 기준 구성은 옵트인 방식입니다. 따라서 기존 AWS 전용 애플리케이션은 공유 bluetape4k Ktor 서버
     * 기본값을 명시적으로 요청하지 않는 한 콘텐츠 협상, 상태 페이지 또는 상태 확인 경로를 설치하지 않습니다.
     */
    fun ktorCore(config: Bluetape4kKtorCoreConfig = Bluetape4kKtorCoreConfig()) {
        ktorCoreConfig = config
    }

    /**
     * 플러그인이 생성한 클라이언트에 전역 Ktor [io.ktor.client.HttpClient] 사용자 정의 설정을 추가합니다.
     */
    fun httpClient(customizer: AwsKtorHttpClientCustomizer) {
        httpClientCustomizers += customizer
    }

    /**
     * 전역 SQS 비동기 클라이언트 빌더 사용자 정의 설정을 추가합니다.
     */
    fun sqsAsyncClient(customizer: AwsKtorSqsAsyncClientCustomizer) {
        sqsAsyncClientCustomizers += customizer
    }

    /**
     * 전역 CloudWatch 비동기 클라이언트 빌더 사용자 정의 설정을 추가합니다.
     */
    fun cloudWatchAsyncClient(customizer: AwsKtorCloudWatchAsyncClientCustomizer) {
        cloudWatchAsyncClientCustomizers += customizer
    }

    /**
     * 전역 CloudWatch Logs 비동기 클라이언트 빌더 사용자 정의 설정을 추가합니다.
     */
    fun cloudWatchLogsAsyncClient(customizer: AwsKtorCloudWatchLogsAsyncClientCustomizer) {
        cloudWatchLogsAsyncClientCustomizers += customizer
    }

    /**
     * 전역 EventBridge 비동기 클라이언트 빌더 사용자 정의 설정을 추가합니다.
     */
    fun eventBridgeAsyncClient(customizer: AwsKtorEventBridgeAsyncClientCustomizer) {
        eventBridgeAsyncClientCustomizers += customizer
    }

    /**
     * 전역 Kinesis 비동기 클라이언트 빌더 사용자 정의 설정을 추가합니다.
     */
    fun kinesisAsyncClient(customizer: AwsKtorKinesisAsyncClientCustomizer) {
        kinesisAsyncClientCustomizers += customizer
    }

    /**
     * 전역 S3 Control 비동기 클라이언트 빌더 사용자 정의 설정을 추가합니다.
     */
    fun s3ControlAsyncClient(customizer: AwsKtorS3ControlAsyncClientCustomizer) {
        s3ControlAsyncClientCustomizers += customizer
    }

    /**
     * 전역 S3 Vectors 비동기 클라이언트 빌더 사용자 정의 설정을 추가합니다.
     */
    fun s3VectorsAsyncClient(customizer: AwsKtorS3VectorsAsyncClientCustomizer) {
        s3VectorsAsyncClientCustomizers += customizer
    }

    /**
     * 전역 SES v2 비동기 클라이언트 빌더 사용자 정의 설정을 추가합니다.
     */
    fun sesV2AsyncClient(customizer: AwsKtorSesV2AsyncClientCustomizer) {
        sesV2AsyncClientCustomizers += customizer
    }

    /**
     * 전역 SNS 비동기 클라이언트 빌더 사용자 정의 설정을 추가합니다.
     */
    fun snsAsyncClient(customizer: AwsKtorSnsAsyncClientCustomizer) {
        snsAsyncClientCustomizers += customizer
    }

    /**
     * 전역 AWS Kotlin SDK DynamoDB 클라이언트 빌더 사용자 정의 설정을 추가합니다.
     */
    fun dynamoDbClient(customizer: AwsKtorDynamoDbClientCustomizer) {
        dynamoDbClientCustomizers += customizer
    }

    /**
     * 전역 STS 비동기 클라이언트 빌더 사용자 정의 설정을 추가합니다.
     */
    fun stsAsyncClient(customizer: AwsKtorStsAsyncClientCustomizer) {
        stsAsyncClientCustomizers += customizer
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
            eventBridgeAsyncClientCustomizers = eventBridgeAsyncClientCustomizers.toList(),
            kinesisAsyncClientCustomizers = kinesisAsyncClientCustomizers.toList(),
            s3ControlAsyncClientCustomizers = s3ControlAsyncClientCustomizers.toList(),
            s3VectorsAsyncClientCustomizers = s3VectorsAsyncClientCustomizers.toList(),
            sesV2AsyncClientCustomizers = sesV2AsyncClientCustomizers.toList(),
            snsAsyncClientCustomizers = snsAsyncClientCustomizers.toList(),
            dynamoDbClientCustomizers = dynamoDbClientCustomizers.toList(),
            stsAsyncClientCustomizers = stsAsyncClientCustomizers.toList(),
        )
}

/**
 * 플러그인이 생성할 Ktor HTTP 클라이언트를 빌드 전에 사용자 정의합니다.
 */
fun interface AwsKtorHttpClientCustomizer {
    fun customize(config: HttpClientConfig<*>)
}

/**
 * 플러그인이 생성할 AWS SDK Java v2 SQS 비동기 클라이언트 빌더를 사용자 정의합니다.
 */
fun interface AwsKtorSqsAsyncClientCustomizer {
    fun customize(builder: SqsAsyncClientBuilder)
}

/**
 * 플러그인이 생성할 AWS SDK Java v2 CloudWatch 비동기 클라이언트 빌더를 사용자 정의합니다.
 */
fun interface AwsKtorCloudWatchAsyncClientCustomizer {
    fun customize(builder: CloudWatchAsyncClientBuilder)
}

/**
 * 플러그인이 생성할 AWS SDK Java v2 CloudWatch Logs 비동기 클라이언트 빌더를 사용자 정의합니다.
 */
fun interface AwsKtorCloudWatchLogsAsyncClientCustomizer {
    fun customize(builder: CloudWatchLogsAsyncClientBuilder)
}

/**
 * 플러그인이 생성할 AWS SDK Java v2 EventBridge 비동기 클라이언트 빌더를 사용자 정의합니다.
 */
fun interface AwsKtorEventBridgeAsyncClientCustomizer {
    fun customize(builder: EventBridgeAsyncClientBuilder)
}

/**
 * 플러그인이 생성할 AWS SDK Java v2 Kinesis 비동기 클라이언트 빌더를 사용자 정의합니다.
 */
fun interface AwsKtorKinesisAsyncClientCustomizer {
    fun customize(builder: KinesisAsyncClientBuilder)
}

/**
 * 플러그인이 생성할 AWS SDK Java v2 S3 Control 비동기 클라이언트 빌더를 사용자 정의합니다.
 */
fun interface AwsKtorS3ControlAsyncClientCustomizer {
    fun customize(builder: S3ControlAsyncClientBuilder)
}

/**
 * 플러그인이 생성할 AWS SDK Java v2 S3 Vectors 비동기 클라이언트 빌더를 사용자 정의합니다.
 */
fun interface AwsKtorS3VectorsAsyncClientCustomizer {
    fun customize(builder: S3VectorsAsyncClientBuilder)
}

/**
 * 플러그인이 생성할 AWS SDK Java v2 SES v2 비동기 클라이언트 빌더를 사용자 정의합니다.
 */
fun interface AwsKtorSesV2AsyncClientCustomizer {
    fun customize(builder: SesV2AsyncClientBuilder)
}

/**
 * 플러그인이 생성할 AWS SDK Java v2 SNS 비동기 클라이언트 빌더를 사용자 정의합니다.
 */
fun interface AwsKtorSnsAsyncClientCustomizer {
    fun customize(builder: SnsAsyncClientBuilder)
}

/**
 * 플러그인이 생성할 AWS Kotlin SDK DynamoDB 클라이언트 빌더를 사용자 정의합니다.
 */
fun interface AwsKtorDynamoDbClientCustomizer {
    fun customize(builder: aws.sdk.kotlin.services.dynamodb.DynamoDbClient.Config.Builder)
}

/**
 * 플러그인이 생성할 AWS SDK Java v2 STS 비동기 클라이언트 빌더를 사용자 정의합니다.
 */
fun interface AwsKtorStsAsyncClientCustomizer {
    fun customize(builder: StsAsyncClientBuilder)
}
