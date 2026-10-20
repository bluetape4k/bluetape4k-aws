package io.bluetape4k.aws.ktor

import aws.sdk.kotlin.services.cloudwatch.CloudWatchClient
import aws.sdk.kotlin.services.cloudwatchlogs.CloudWatchLogsClient
import aws.sdk.kotlin.services.eventbridge.EventBridgeClient
import aws.sdk.kotlin.services.kinesis.KinesisClient
import aws.sdk.kotlin.services.s3control.S3ControlClient
import aws.sdk.kotlin.services.s3vectors.S3VectorsClient
import aws.sdk.kotlin.services.sesv2.SesV2Client
import aws.sdk.kotlin.services.sns.SnsClient
import aws.sdk.kotlin.services.sqs.SqsClient
import aws.sdk.kotlin.services.sts.StsClient
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
 * 실행 중인 협력 객체 속성은 transient입니다. 명시적으로 전달한 AWS Kotlin 자격 증명 공급자와
 * HTTP 엔진은 호출자 소유입니다. 서비스 클라이언트를 닫은 뒤 호출자가 해당 자원을 닫아야 합니다.
 */
class AwsKtorDefaults(
    val region: String? = null,
    endpointOverride: Url? = null,
    @Transient val kotlinCredentialsProvider: CredentialsProvider? = null,
    @Transient val signingClock: Clock? = null,
    @Transient val kotlinHttpClient: HttpClientEngine? = null,
    cloudWatchClientCustomizers: List<AwsKtorCloudWatchClientCustomizer> = emptyList(),
    cloudWatchLogsClientCustomizers: List<AwsKtorCloudWatchLogsClientCustomizer> = emptyList(),
    eventBridgeClientCustomizers: List<AwsKtorEventBridgeClientCustomizer> = emptyList(),
    kinesisClientCustomizers: List<AwsKtorKinesisClientCustomizer> = emptyList(),
    s3ControlClientCustomizers: List<AwsKtorS3ControlClientCustomizer> = emptyList(),
    s3VectorsClientCustomizers: List<AwsKtorS3VectorsClientCustomizer> = emptyList(),
    sesV2ClientCustomizers: List<AwsKtorSesV2ClientCustomizer> = emptyList(),
    snsClientCustomizers: List<AwsKtorSnsClientCustomizer> = emptyList(),
    sqsClientCustomizers: List<AwsKtorSqsClientCustomizer> = emptyList(),
    stsClientCustomizers: List<AwsKtorStsClientCustomizer> = emptyList(),
    httpClientCustomizers: List<AwsKtorHttpClientCustomizer> = emptyList(),
    dynamoDbClientCustomizers: List<AwsKtorDynamoDbClientCustomizer> = emptyList(),
    val allowInsecureLocalHttpEndpoint: Boolean = false,
): AbstractValueObject() {

    private val endpointOverrideValue: String? = endpointOverride?.toString()

    @Transient
    private val cloudWatchClientCustomizersValue: List<AwsKtorCloudWatchClientCustomizer>? = cloudWatchClientCustomizers

    @Transient
    private val cloudWatchLogsClientCustomizersValue: List<AwsKtorCloudWatchLogsClientCustomizer>? =
        cloudWatchLogsClientCustomizers

    @Transient
    private val eventBridgeClientCustomizersValue: List<AwsKtorEventBridgeClientCustomizer>? = eventBridgeClientCustomizers

    @Transient
    private val kinesisClientCustomizersValue: List<AwsKtorKinesisClientCustomizer>? = kinesisClientCustomizers

    @Transient
    private val s3ControlClientCustomizersValue: List<AwsKtorS3ControlClientCustomizer>? = s3ControlClientCustomizers

    @Transient
    private val s3VectorsClientCustomizersValue: List<AwsKtorS3VectorsClientCustomizer>? = s3VectorsClientCustomizers

    @Transient
    private val sesV2ClientCustomizersValue: List<AwsKtorSesV2ClientCustomizer>? = sesV2ClientCustomizers

    @Transient
    private val snsClientCustomizersValue: List<AwsKtorSnsClientCustomizer>? = snsClientCustomizers

    @Transient
    private val sqsClientCustomizersValue: List<AwsKtorSqsClientCustomizer>? = sqsClientCustomizers

    @Transient
    private val stsClientCustomizersValue: List<AwsKtorStsClientCustomizer>? = stsClientCustomizers

    @Transient
    private val httpClientCustomizersValue: List<AwsKtorHttpClientCustomizer>? = httpClientCustomizers

    @Transient
    private val dynamoDbClientCustomizersValue: List<AwsKtorDynamoDbClientCustomizer>? = dynamoDbClientCustomizers

    init {
        require(endpointOverrideValue == null || !region.isNullOrBlank()) {
            "region is required when endpointOverride is configured."
        }
    }

    /** 테스트와 로컬 개발에서 주로 LocalStack을 지정하는 공유 엔드포인트 재정의입니다. */
    val endpointOverride: Url?
        get() = endpointOverrideValue?.let(::Url)

    /** 플러그인이 생성한 AWS Kotlin SDK CloudWatch 클라이언트의 공유 사용자 정의 설정입니다. */
    val cloudWatchClientCustomizers: List<AwsKtorCloudWatchClientCustomizer>
        get() = cloudWatchClientCustomizersValue ?: emptyList()

    /** 플러그인이 생성한 AWS Kotlin SDK CloudWatch Logs 클라이언트의 공유 사용자 정의 설정입니다. */
    val cloudWatchLogsClientCustomizers: List<AwsKtorCloudWatchLogsClientCustomizer>
        get() = cloudWatchLogsClientCustomizersValue ?: emptyList()

    /** 플러그인이 생성한 AWS Kotlin SDK EventBridge 클라이언트의 공유 사용자 정의 설정입니다. */
    val eventBridgeClientCustomizers: List<AwsKtorEventBridgeClientCustomizer>
        get() = eventBridgeClientCustomizersValue ?: emptyList()

    /** 플러그인이 생성한 AWS Kotlin SDK Kinesis 클라이언트의 공유 사용자 정의 설정입니다. */
    val kinesisClientCustomizers: List<AwsKtorKinesisClientCustomizer>
        get() = kinesisClientCustomizersValue ?: emptyList()

    /** 플러그인이 생성한 AWS Kotlin SDK S3 Control 클라이언트의 공유 사용자 정의 설정입니다. */
    val s3ControlClientCustomizers: List<AwsKtorS3ControlClientCustomizer>
        get() = s3ControlClientCustomizersValue ?: emptyList()

    /** 플러그인이 생성한 AWS Kotlin SDK S3 Vectors 클라이언트의 공유 사용자 정의 설정입니다. */
    val s3VectorsClientCustomizers: List<AwsKtorS3VectorsClientCustomizer>
        get() = s3VectorsClientCustomizersValue ?: emptyList()

    /** 플러그인이 생성한 AWS Kotlin SDK SES v2 클라이언트의 공유 사용자 정의 설정입니다. */
    val sesV2ClientCustomizers: List<AwsKtorSesV2ClientCustomizer>
        get() = sesV2ClientCustomizersValue ?: emptyList()

    /** 플러그인이 생성한 AWS Kotlin SDK SNS 클라이언트의 공유 사용자 정의 설정입니다. */
    val snsClientCustomizers: List<AwsKtorSnsClientCustomizer>
        get() = snsClientCustomizersValue ?: emptyList()

    /** 플러그인이 생성한 AWS Kotlin SDK SQS 클라이언트의 공유 사용자 정의 설정입니다. */
    val sqsClientCustomizers: List<AwsKtorSqsClientCustomizer>
        get() = sqsClientCustomizersValue ?: emptyList()

    /** 플러그인이 생성한 AWS Kotlin SDK STS 클라이언트의 공유 사용자 정의 설정입니다. */
    val stsClientCustomizers: List<AwsKtorStsClientCustomizer>
        get() = stsClientCustomizersValue ?: emptyList()

    /** 플러그인이 생성한 Ktor HTTP 클라이언트의 공유 사용자 정의 설정입니다. */
    val httpClientCustomizers: List<AwsKtorHttpClientCustomizer>
        get() = httpClientCustomizersValue ?: emptyList()

    /** 플러그인이 생성한 AWS Kotlin SDK DynamoDB 클라이언트의 공유 사용자 정의 설정입니다. */
    val dynamoDbClientCustomizers: List<AwsKtorDynamoDbClientCustomizer>
        get() = dynamoDbClientCustomizersValue ?: emptyList()

    override fun equalProperties(other: Any): Boolean =
        other is AwsKtorDefaults &&
                region == other.region &&
                endpointOverrideValue == other.endpointOverrideValue &&
                allowInsecureLocalHttpEndpoint == other.allowInsecureLocalHttpEndpoint &&
                kotlinCredentialsProvider == other.kotlinCredentialsProvider &&
                signingClock == other.signingClock &&
                kotlinHttpClient == other.kotlinHttpClient &&
                cloudWatchClientCustomizers == other.cloudWatchClientCustomizers &&
                cloudWatchLogsClientCustomizers == other.cloudWatchLogsClientCustomizers &&
                eventBridgeClientCustomizers == other.eventBridgeClientCustomizers &&
                kinesisClientCustomizers == other.kinesisClientCustomizers &&
                s3ControlClientCustomizers == other.s3ControlClientCustomizers &&
                s3VectorsClientCustomizers == other.s3VectorsClientCustomizers &&
                sesV2ClientCustomizers == other.sesV2ClientCustomizers &&
                snsClientCustomizers == other.snsClientCustomizers &&
                sqsClientCustomizers == other.sqsClientCustomizers &&
                stsClientCustomizers == other.stsClientCustomizers &&
                httpClientCustomizers == other.httpClientCustomizers &&
                dynamoDbClientCustomizers == other.dynamoDbClientCustomizers

    override fun equals(other: Any?): Boolean = other != null && super.equals(other)

    override fun hashCode(): Int = hashOf(
        region,
        endpointOverrideValue,
        allowInsecureLocalHttpEndpoint,
        kotlinCredentialsProvider,
        signingClock,
        kotlinHttpClient,
        cloudWatchClientCustomizers,
        cloudWatchLogsClientCustomizers,
        eventBridgeClientCustomizers,
        kinesisClientCustomizers,
        s3ControlClientCustomizers,
        s3VectorsClientCustomizers,
        sesV2ClientCustomizers,
        snsClientCustomizers,
        sqsClientCustomizers,
        stsClientCustomizers,
        httpClientCustomizers,
        dynamoDbClientCustomizers,
    )

    override fun buildStringHelper(): ToStringBuilder =
        super.buildStringHelper()
            .add("region", region)
            .add("endpointOverrideConfigured", endpointOverrideValue != null)
            .add("allowInsecureLocalHttpEndpoint", allowInsecureLocalHttpEndpoint)
            .add("kotlinCredentialsProviderConfigured", kotlinCredentialsProvider != null)
            .add("signingClockConfigured", signingClock != null)
            .add("kotlinHttpClientConfigured", kotlinHttpClient != null)
            .add("cloudWatchClientCustomizersCount", cloudWatchClientCustomizers.size)
            .add("cloudWatchLogsClientCustomizersCount", cloudWatchLogsClientCustomizers.size)
            .add("eventBridgeClientCustomizersCount", eventBridgeClientCustomizers.size)
            .add("kinesisClientCustomizersCount", kinesisClientCustomizers.size)
            .add("s3ControlClientCustomizersCount", s3ControlClientCustomizers.size)
            .add("s3VectorsClientCustomizersCount", s3VectorsClientCustomizers.size)
            .add("sesV2ClientCustomizersCount", sesV2ClientCustomizers.size)
            .add("snsClientCustomizersCount", snsClientCustomizers.size)
            .add("sqsClientCustomizersCount", sqsClientCustomizers.size)
            .add("stsClientCustomizersCount", stsClientCustomizers.size)
            .add("httpClientCustomizersCount", httpClientCustomizers.size)
            .add("dynamoDbClientCustomizersCount", dynamoDbClientCustomizers.size)

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

    /** 로컬 loopback endpoint에서만 HTTP를 허용합니다. 기본값은 false입니다. */
    var allowInsecureLocalHttpEndpoint: Boolean = false

    /** 생성된 AWS Kotlin 서비스 클라이언트에 전달하는 호출자 소유 자격 증명 공급자입니다. */
    var kotlinCredentialsProvider: CredentialsProvider? = null

    /** 결정론적 테스트를 위한 공유 SigV4 서명 시계입니다. */
    var signingClock: Clock? = null

    /**
     * AWS 클라이언트가 공유하는 호출자 소유 HTTP 엔진입니다. 설정하면 모든 클라이언트를 닫은 뒤
     * 호출자가 엔진을 닫아야 합니다.
     */
    var kotlinHttpClient: HttpClientEngine? = null

    private val httpClientCustomizers = mutableListOf<AwsKtorHttpClientCustomizer>()
    private val dynamoDbClientCustomizers = mutableListOf<AwsKtorDynamoDbClientCustomizer>()
    private val cloudWatchClientCustomizers = mutableListOf<AwsKtorCloudWatchClientCustomizer>()
    private val cloudWatchLogsClientCustomizers = mutableListOf<AwsKtorCloudWatchLogsClientCustomizer>()
    private val eventBridgeClientCustomizers = mutableListOf<AwsKtorEventBridgeClientCustomizer>()
    private val kinesisClientCustomizers = mutableListOf<AwsKtorKinesisClientCustomizer>()
    private val s3ControlClientCustomizers = mutableListOf<AwsKtorS3ControlClientCustomizer>()
    private val s3VectorsClientCustomizers = mutableListOf<AwsKtorS3VectorsClientCustomizer>()
    private val sesV2ClientCustomizers = mutableListOf<AwsKtorSesV2ClientCustomizer>()
    private val snsClientCustomizers = mutableListOf<AwsKtorSnsClientCustomizer>()
    private val sqsClientCustomizers = mutableListOf<AwsKtorSqsClientCustomizer>()
    private val stsClientCustomizers = mutableListOf<AwsKtorStsClientCustomizer>()

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

    /** 전역 AWS Kotlin SDK CloudWatch 클라이언트 빌더 사용자 정의 설정을 추가합니다. */
    fun cloudWatchClient(customizer: AwsKtorCloudWatchClientCustomizer) {
        cloudWatchClientCustomizers += customizer
    }

    /** 전역 AWS Kotlin SDK CloudWatch Logs 클라이언트 빌더 사용자 정의 설정을 추가합니다. */
    fun cloudWatchLogsClient(customizer: AwsKtorCloudWatchLogsClientCustomizer) {
        cloudWatchLogsClientCustomizers += customizer
    }

    /** 전역 AWS Kotlin SDK EventBridge 클라이언트 빌더 사용자 정의 설정을 추가합니다. */
    fun eventBridgeClient(customizer: AwsKtorEventBridgeClientCustomizer) {
        eventBridgeClientCustomizers += customizer
    }

    /** 전역 AWS Kotlin SDK Kinesis 클라이언트 빌더 사용자 정의 설정을 추가합니다. */
    fun kinesisClient(customizer: AwsKtorKinesisClientCustomizer) {
        kinesisClientCustomizers += customizer
    }

    /** 전역 AWS Kotlin SDK S3 Control 클라이언트 빌더 사용자 정의 설정을 추가합니다. */
    fun s3ControlClient(customizer: AwsKtorS3ControlClientCustomizer) {
        s3ControlClientCustomizers += customizer
    }

    /** 전역 AWS Kotlin SDK S3 Vectors 클라이언트 빌더 사용자 정의 설정을 추가합니다. */
    fun s3VectorsClient(customizer: AwsKtorS3VectorsClientCustomizer) {
        s3VectorsClientCustomizers += customizer
    }

    /** 전역 AWS Kotlin SDK SES v2 클라이언트 빌더 사용자 정의 설정을 추가합니다. */
    fun sesV2Client(customizer: AwsKtorSesV2ClientCustomizer) {
        sesV2ClientCustomizers += customizer
    }

    /** 전역 AWS Kotlin SDK SNS 클라이언트 빌더 사용자 정의 설정을 추가합니다. */
    fun snsClient(customizer: AwsKtorSnsClientCustomizer) {
        snsClientCustomizers += customizer
    }

    /** 전역 AWS Kotlin SDK SQS 클라이언트 빌더 사용자 정의 설정을 추가합니다. */
    fun sqsClient(customizer: AwsKtorSqsClientCustomizer) {
        sqsClientCustomizers += customizer
    }

    /** 전역 AWS Kotlin SDK STS 클라이언트 빌더 사용자 정의 설정을 추가합니다. */
    fun stsClient(customizer: AwsKtorStsClientCustomizer) {
        stsClientCustomizers += customizer
    }

    /**
     * 전역 AWS Kotlin SDK DynamoDB 클라이언트 빌더 사용자 정의 설정을 추가합니다.
     */
    fun dynamoDbClient(customizer: AwsKtorDynamoDbClientCustomizer) {
        dynamoDbClientCustomizers += customizer
    }

    internal fun toDefaults(): AwsKtorDefaults =
        AwsKtorDefaults(
            region = region,
            endpointOverride = endpointOverride,
            kotlinCredentialsProvider = kotlinCredentialsProvider,
            signingClock = signingClock,
            kotlinHttpClient = kotlinHttpClient,
            cloudWatchClientCustomizers = cloudWatchClientCustomizers.toList(),
            cloudWatchLogsClientCustomizers = cloudWatchLogsClientCustomizers.toList(),
            eventBridgeClientCustomizers = eventBridgeClientCustomizers.toList(),
            kinesisClientCustomizers = kinesisClientCustomizers.toList(),
            s3ControlClientCustomizers = s3ControlClientCustomizers.toList(),
            s3VectorsClientCustomizers = s3VectorsClientCustomizers.toList(),
            sesV2ClientCustomizers = sesV2ClientCustomizers.toList(),
            snsClientCustomizers = snsClientCustomizers.toList(),
            sqsClientCustomizers = sqsClientCustomizers.toList(),
            stsClientCustomizers = stsClientCustomizers.toList(),
            httpClientCustomizers = httpClientCustomizers.toList(),
            dynamoDbClientCustomizers = dynamoDbClientCustomizers.toList(),
            allowInsecureLocalHttpEndpoint = allowInsecureLocalHttpEndpoint,
        )

    override fun toString(): String =
        ToStringBuilder(this)
            .add("region", region)
            .add("endpointOverrideConfigured", endpointOverride != null)
            .add("allowInsecureLocalHttpEndpoint", allowInsecureLocalHttpEndpoint)
            .add("kotlinCredentialsProviderConfigured", kotlinCredentialsProvider != null)
            .add("signingClockConfigured", signingClock != null)
            .add("kotlinHttpClientConfigured", kotlinHttpClient != null)
            .toString()
}

/**
 * 플러그인이 생성할 Ktor HTTP 클라이언트를 빌드 전에 사용자 정의합니다.
 */
fun interface AwsKtorHttpClientCustomizer {
    fun customize(config: HttpClientConfig<*>)
}

/** 플러그인이 생성할 AWS Kotlin SDK CloudWatch 클라이언트 빌더를 사용자 정의합니다. */
fun interface AwsKtorCloudWatchClientCustomizer {
    fun customize(builder: CloudWatchClient.Config.Builder)
}

/** 플러그인이 생성할 AWS Kotlin SDK CloudWatch Logs 클라이언트 빌더를 사용자 정의합니다. */
fun interface AwsKtorCloudWatchLogsClientCustomizer {
    fun customize(builder: CloudWatchLogsClient.Config.Builder)
}

/** 플러그인이 생성할 AWS Kotlin SDK EventBridge 클라이언트 빌더를 사용자 정의합니다. */
fun interface AwsKtorEventBridgeClientCustomizer {
    fun customize(builder: EventBridgeClient.Config.Builder)
}

/** 플러그인이 생성할 AWS Kotlin SDK Kinesis 클라이언트 빌더를 사용자 정의합니다. */
fun interface AwsKtorKinesisClientCustomizer {
    fun customize(builder: KinesisClient.Config.Builder)
}

/** 플러그인이 생성할 AWS Kotlin SDK S3 Control 클라이언트 빌더를 사용자 정의합니다. */
fun interface AwsKtorS3ControlClientCustomizer {
    fun customize(builder: S3ControlClient.Config.Builder)
}

/** 플러그인이 생성할 AWS Kotlin SDK S3 Vectors 클라이언트 빌더를 사용자 정의합니다. */
fun interface AwsKtorS3VectorsClientCustomizer {
    fun customize(builder: S3VectorsClient.Config.Builder)
}

/** 플러그인이 생성할 AWS Kotlin SDK SES v2 클라이언트 빌더를 사용자 정의합니다. */
fun interface AwsKtorSesV2ClientCustomizer {
    fun customize(builder: SesV2Client.Config.Builder)
}

/** 플러그인이 생성할 AWS Kotlin SDK SNS 클라이언트 빌더를 사용자 정의합니다. */
fun interface AwsKtorSnsClientCustomizer {
    fun customize(builder: SnsClient.Config.Builder)
}

/** 플러그인이 생성할 AWS Kotlin SDK SQS 클라이언트 빌더를 사용자 정의합니다. */
fun interface AwsKtorSqsClientCustomizer {
    fun customize(builder: SqsClient.Config.Builder)
}

/** 플러그인이 생성할 AWS Kotlin SDK STS 클라이언트 빌더를 사용자 정의합니다. */
fun interface AwsKtorStsClientCustomizer {
    fun customize(builder: StsClient.Config.Builder)
}

/**
 * 플러그인이 생성할 AWS Kotlin SDK DynamoDB 클라이언트 빌더를 사용자 정의합니다.
 */
fun interface AwsKtorDynamoDbClientCustomizer {
    fun customize(builder: aws.sdk.kotlin.services.dynamodb.DynamoDbClient.Config.Builder)
}
