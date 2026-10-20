package io.bluetape4k.aws.ktor.client

import aws.smithy.kotlin.runtime.auth.awssigning.AwsSignatureType
import aws.smithy.kotlin.runtime.auth.awssigning.AwsSigner
import aws.smithy.kotlin.runtime.auth.awssigning.AwsSigningConfig
import aws.smithy.kotlin.runtime.auth.awssigning.AwsSignedBodyHeader
import aws.smithy.kotlin.runtime.auth.awssigning.DefaultAwsSigner
import aws.smithy.kotlin.runtime.auth.awssigning.HashSpecification
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.collections.emptyAttributes
import aws.smithy.kotlin.runtime.http.HttpBody
import aws.smithy.kotlin.runtime.http.HttpMethod as SmithyHttpMethod
import aws.smithy.kotlin.runtime.http.request.HttpRequest as SmithyHttpRequest
import aws.smithy.kotlin.runtime.http.request.headers
import aws.smithy.kotlin.runtime.http.request.url
import aws.smithy.kotlin.runtime.net.url.Url as SmithyUrl
import aws.smithy.kotlin.runtime.time.Instant as SmithyInstant
import io.bluetape4k.aws.ktor.validateAwsKotlinServiceEndpoint
import io.bluetape4k.support.requireNotBlank
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.takeFrom
import java.time.Clock

/**
 * Ktor `HttpClient` 요청에 AWS Signature Version 4 서명을 적용하는 플러그인입니다.
 *
 * AWS Kotlin SDK의 Smithy 서명기와 caller-owned 자격 증명 공급자를 사용합니다. endpoint는 HTTPS를
 * 사용해야 하며, 로컬 emulator에 HTTP가 필요하면 `allowInsecureLocalHttpEndpoint = true`를 명시합니다.
 * 서명 본문은 replay 가능한 `ByteArrayContent`로 제한됩니다. 스트리밍 본문은
 * `payloadSigningEnabled = false`일 때 버퍼링 없이 전달합니다.
 *
 * ```kotlin
 * import aws.sdk.kotlin.runtime.auth.credentials.DefaultChainCredentialsProvider
 * import io.ktor.client.HttpClient
 * import io.ktor.client.engine.cio.CIO
 *
 * val client = HttpClient(CIO) {
 *     install(AwsSigV4Plugin) {
 *         region = "ap-northeast-2"
 *         service = "execute-api"
 *         credentialsProvider = DefaultChainCredentialsProvider()
 *     }
 * }
 * ```
 */
val AwsSigV4Plugin = createClientPlugin("AwsSigV4Plugin", ::AwsSigV4PluginConfig) {
    val options = pluginConfig.toOptions()

    on(Send) { request ->
        options.sign(request)
        proceed(request)
    }
}

private data class AwsSigV4Options(
    val region: String,
    val service: String,
    val credentialsProvider: CredentialsProvider,
    val authLocation: AwsSigV4AuthLocation,
    val doubleUrlEncode: Boolean,
    val normalizePath: Boolean,
    val payloadSigningEnabled: Boolean,
    val signedBodyHeader: AwsSignedBodyHeader,
    val allowInsecureLocalHttpEndpoint: Boolean,
    val signingClock: Clock?,
    val signer: AwsSigner,
)

private fun AwsSigV4PluginConfig.toOptions(): AwsSigV4Options {
    region.requireNotBlank("region")
    service.requireNotBlank("service")

    return AwsSigV4Options(
        region = region,
        service = service,
        credentialsProvider = requireNotNull(credentialsProvider) {
            "credentialsProvider must be supplied by the caller."
        },
        authLocation = authLocation,
        doubleUrlEncode = doubleUrlEncode,
        normalizePath = normalizePath,
        payloadSigningEnabled = payloadSigningEnabled,
        signedBodyHeader = signedBodyHeader,
        allowInsecureLocalHttpEndpoint = allowInsecureLocalHttpEndpoint,
        signingClock = signingClock,
        signer = signer,
    )
}

private suspend fun AwsSigV4Options.sign(request: HttpRequestBuilder) {
    val requestUrl = request.url.build()
    validateAwsKotlinServiceEndpoint(
        endpoint = requestUrl.toString(),
        region = region,
        allowInsecureLocalHttpEndpoint = allowInsecureLocalHttpEndpoint,
    )

    val content = request.body as? OutgoingContent ?: EmptyAwsContent
    val signingBody = content.toSigningBody(payloadSigningEnabled)
    val smithyUrl = SmithyUrl.parse(requestUrl.toString())
    val signingRequest = SmithyHttpRequest {
        method = SmithyHttpMethod.parse(request.method.value)
        url(smithyUrl)
        headers {
            request.headers.entries().forEach { (name, values) ->
                if (!name.equals(HttpHeaders.Host, ignoreCase = true)) {
                    values.forEach { value -> append(name, value) }
                }
            }
            content.headers.entries().forEach { (name, values) ->
                if (!name.equals(HttpHeaders.Host, ignoreCase = true)) {
                    values.forEach { value -> append(name, value) }
                }
            }
            append(HttpHeaders.Host, smithyUrl.hostAndPort)
        }
        body = signingBody
    }

    val credentials = credentialsProvider.resolve(emptyAttributes())
    val signingRegion = region
    val signingService = service
    val bodyHeader = signedBodyHeader
    val signed = signer.sign(
        signingRequest,
        AwsSigningConfig {
            this.credentials = credentials
            this.region = signingRegion
            this.service = signingService
            signingClock?.let { signingDate = SmithyInstant.fromIso8601(it.instant().toString()) }
            signatureType = authLocation.toSmithySignatureType()
            useDoubleUriEncode = doubleUrlEncode
            normalizeUriPath = normalizePath
            hashSpecification = if (payloadSigningEnabled) {
                HashSpecification.CalculateFromPayload
            } else {
                HashSpecification.UnsignedPayload
            }
            this.signedBodyHeader = bodyHeader
        },
    ).output

    request.applySignedRequest(signed, content)
}

private fun OutgoingContent.toSigningBody(payloadSigningEnabled: Boolean): HttpBody =
    when (this) {
        is OutgoingContent.NoContent -> HttpBody.Empty
        is OutgoingContent.ByteArrayContent ->
            if (payloadSigningEnabled) HttpBody.fromBytes(bytes()) else HttpBody.Empty
        is OutgoingContent.ContentWrapper -> delegate().toSigningBody(payloadSigningEnabled)
        else -> {
            check(!payloadSigningEnabled) {
                "AwsSigV4Plugin can sign only replayable ByteArrayContent payloads. " +
                    "Set payloadSigningEnabled=false for streaming content."
            }
            HttpBody.Empty
        }
    }

private fun HttpRequestBuilder.applySignedRequest(
    signedRequest: SmithyHttpRequest,
    content: OutgoingContent,
) {
    val signedUrl = Url(signedRequest.url.toString())
    url.takeFrom(signedUrl)
    check(url.build().toString() == signedRequest.url.toString()) {
        "AwsSigV4Plugin cannot represent the signed URI exactly."
    }

    val contentHeaders = content.headers.entries()
        .associate { (name, values) -> name.lowercase() to values }
    val originalHeaderNames = headers.entries().map { it.key.lowercase() }.toSet()
    originalHeaderNames.forEach(headers::remove)

    signedRequest.headers.entries().forEach { (name, signedValues) ->
        val bodyValues = contentHeaders[name.lowercase()]
        val requestValues = if (bodyValues == null) {
            signedValues
        } else {
            check(signedValues.takeLast(bodyValues.size) == bodyValues) {
                "AwsSigV4Plugin cannot represent the signed body headers exactly."
            }
            signedValues.dropLast(bodyValues.size)
        }
        requestValues.forEach { value -> headers.append(name, value) }
    }
}

private fun AwsSigV4AuthLocation.toSmithySignatureType(): AwsSignatureType =
    when (this) {
        AwsSigV4AuthLocation.Header -> AwsSignatureType.HTTP_REQUEST_VIA_HEADERS
        AwsSigV4AuthLocation.QueryString -> AwsSignatureType.HTTP_REQUEST_VIA_QUERY_PARAMS
    }

private object EmptyAwsContent: OutgoingContent.NoContent()
