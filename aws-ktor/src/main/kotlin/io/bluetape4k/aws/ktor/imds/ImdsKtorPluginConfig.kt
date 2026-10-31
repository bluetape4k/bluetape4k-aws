package io.bluetape4k.aws.ktor.imds

import aws.sdk.kotlin.runtime.config.imds.EndpointMode
import aws.smithy.kotlin.runtime.http.SdkHttpClient
import io.bluetape4k.ToStringBuilder
import io.bluetape4k.javatimes.hours
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireZeroOrPositiveNumber
import java.net.URI
import java.time.Duration

/**
 * Ktor IMDS 플러그인 구성입니다.
 *
 * 플러그인 설치는 IMDS를 호출하지 않습니다. 요청은 [ImdsKtorOperations] 메서드가 호출될 때 발생하며,
 * [requestTimeout]은 토큰 발급과 metadata 요청 전체에 적용됩니다.
 */
class ImdsKtorPluginConfig {

    /** Ktor IMDS 런타임 등록을 활성화합니다. */
    var enabled: Boolean = true

    /** 애플리케이션이 소유하는 선택적인 작업 파사드입니다. */
    var imdsOperations: ImdsKtorOperations? = null

    /** AWS 프로필 값보다 우선하는 메타데이터 엔드포인트 재정의입니다. */
    var endpoint: URI? = null

    /** 명시하면 해당 AWS 기본 endpoint family를 사용하고 프로필 endpoint를 대체합니다. */
    var endpointMode: EndpointMode? = EndpointMode.IPv4

    /** 로컬 HTTP endpoint를 명시적으로 허용합니다. 다른 사용자 지정 HTTP endpoint는 허용되지 않습니다. */
    var allowInsecureLocalHttpEndpoint: Boolean = false

    /** IMDSv2 토큰 TTL입니다. IMDS 프로토콜 범위인 1초부터 6시간까지 허용합니다. */
    var tokenTtl: Duration = 6.hours()

    /** 토큰 발급과 metadata 요청 전체에 적용하는 타임아웃입니다. */
    var requestTimeout: Duration = 1.seconds()

    /** IMDS 요청의 추가 재시도 횟수입니다. 0이면 재시도하지 않습니다. */
    var retries: Int = 0

    internal var profileResolver: ImdsProfileResolver = ImdsProfileResolver()

    internal fun toRuntime(): ImdsKtorRuntime? {
        if (!enabled) return null
        imdsOperations?.let { return ImdsKtorRuntime(it) }

        validate()

        val profile = profileResolver.resolve(
            resolveEndpoint = endpoint == null,
            resolveEndpointMode = endpoint == null && endpointMode == null,
        )
        val resolvedMode = endpointMode ?: profile.endpointMode ?: EndpointMode.IPv4
        val resolvedEndpoint = endpoint ?: profile.endpoint
        val validatedEndpoint = ImdsEndpointPolicy.resolve(
            endpoint = resolvedEndpoint,
            endpointMode = resolvedMode,
            allowInsecureLocalHttpEndpoint = allowInsecureLocalHttpEndpoint,
        )
        val metadataTimeout = profile.metadataServiceTimeout ?: 1.seconds()
        val engine = ImdsHttpClientFactory.createEngine(metadataTimeout)

        return try {
            val adapter = ImdsSmithyAdapter(
                client = SdkHttpClient(engine),
                endpoint = validatedEndpoint,
                tokenTtl = tokenTtl,
                retries = retries,
            )
            ImdsKtorRuntime(
                operations = ImdsKtorTemplate(adapter, requestTimeout),
                ownedEngine = engine,
            )
        } catch (cause: Throwable) {
            try {
                engine.close()
            } catch (cleanupFailure: Throwable) {
                if (cleanupFailure !== cause) cause.addSuppressed(cleanupFailure)
            }
            throw cause
        }
    }

    private fun validate() {
        require(tokenTtl >= 1.seconds() && tokenTtl <= 6.hours()) {
            "tokenTtl must be between 1 second and 6 hours."
        }
        requestTimeout.requireGt(Duration.ZERO, "requestTimeout")
        retries.requireZeroOrPositiveNumber("retries")
    }

    override fun toString(): String =
        ToStringBuilder(this)
            .add("enabled", enabled)
            .add("imdsOperationsConfigured", imdsOperations != null)
            .add("endpointConfigured", endpoint != null)
            .add("endpointMode", endpointMode)
            .add("allowInsecureLocalHttpEndpoint", allowInsecureLocalHttpEndpoint)
            .add("tokenTtl", tokenTtl)
            .add("requestTimeout", requestTimeout)
            .add("retries", retries)
            .toString()
}
