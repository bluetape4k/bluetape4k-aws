package io.bluetape4k.aws.ktor.client

import io.bluetape4k.support.requireNotBlank
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.ContentStreamProvider
import software.amazon.awssdk.http.SdkHttpFullRequest
import software.amazon.awssdk.http.SdkHttpMethod
import software.amazon.awssdk.http.SdkHttpRequest
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner
import software.amazon.awssdk.http.auth.spi.signer.HttpSigner
import java.io.Serializable
import java.net.URI
import java.time.Clock

/**
 * Ktor `HttpClient` 요청에 AWS Signature Version 4 서명을 적용하는 플러그인입니다.
 *
 * ## 동작/계약
 * - Ktor `Send` hook에서 최종 [OutgoingContent]로 변환된 요청을 AWS SDK `SdkHttpRequest`로 매핑한다.
 * - [AwsV4HttpSigner]로 헤더 또는 쿼리 문자열 서명을 생성한 뒤 Ktor 요청에 다시 반영한다.
 * - 기본 모드는 replay 가능한 body만 payload 서명한다. 스트리밍 body는 `payloadSigningEnabled=false`일 때만 허용한다.
 *
 * ```kotlin
 * import io.ktor.client.HttpClient
 * import io.ktor.client.engine.cio.CIO
 * import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
 *
 * val client = HttpClient(CIO) {
 *     install(AwsSigV4Plugin) {
 *         region = "ap-northeast-2"
 *         service = "execute-api"
 *         credentialsProvider = DefaultCredentialsProvider.builder().build()
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
    val credentialsProvider: AwsCredentialsProvider,
    val authLocation: AwsSigV4AuthLocation,
    val doubleUrlEncode: Boolean,
    val normalizePath: Boolean,
    val payloadSigningEnabled: Boolean,
    val signingClock: Clock?,
    val signer: AwsV4HttpSigner,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

private fun AwsSigV4PluginConfig.toOptions(): AwsSigV4Options {
    region.requireNotBlank("region")
    service.requireNotBlank("service")

    return AwsSigV4Options(
        region = region,
        service = service,
        credentialsProvider = credentialsProvider,
        authLocation = authLocation,
        doubleUrlEncode = doubleUrlEncode,
        normalizePath = normalizePath,
        payloadSigningEnabled = payloadSigningEnabled,
        signingClock = signingClock,
        signer = signer,
    )
}

private fun AwsSigV4Options.sign(request: HttpRequestBuilder) {
    val body = request.body as? OutgoingContent ?: EmptyAwsContent
    val payload = body.toContentStreamProvider(payloadSigningEnabled)
    val sdkRequest = request.toSdkHttpFullRequest(body, payloadSigningEnabled)
    val credentials = credentialsProvider.resolveCredentials()

    val signedRequest = signer.sign { builder ->
        builder.identity(credentials)
            .request(sdkRequest)
            .putProperty(AwsV4HttpSigner.REGION_NAME, region)
            .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, service)
            .putProperty(AwsV4HttpSigner.DOUBLE_URL_ENCODE, doubleUrlEncode)
            .putProperty(AwsV4HttpSigner.NORMALIZE_PATH, normalizePath)
            .putProperty(AwsV4HttpSigner.AUTH_LOCATION, authLocation.toAwsAuthLocation())
            .putProperty(AwsV4HttpSigner.PAYLOAD_SIGNING_ENABLED, payloadSigningEnabled)

        if (signingClock != null) {
            builder.putProperty(HttpSigner.SIGNING_CLOCK, signingClock)
        }
        if (payload != null) {
            builder.payload(payload)
        }
    }

    request.applySignedRequest(signedRequest.request())
}

private fun HttpRequestBuilder.toSdkHttpFullRequest(
    body: OutgoingContent,
    payloadSigningEnabled: Boolean,
): SdkHttpFullRequest {
    val requestUrl = url.build()
    val builder = SdkHttpFullRequest.builder()
        .uri(URI(requestUrl.toString()))
        .method(SdkHttpMethod.fromValue(method.value))

    headers.entries().forEach { (name, values) ->
        if (!name.equals(HttpHeaders.Host, ignoreCase = true)) {
            builder.putHeader(name, values)
        }
    }
    body.headers.entries().forEach { (name, values) ->
        if (!name.equals(HttpHeaders.Host, ignoreCase = true)) {
            builder.putHeader(name, values)
        }
    }

    body.toContentStreamProvider(payloadSigningEnabled)?.let(builder::contentStreamProvider)
    return builder.build()
}

private fun OutgoingContent.toContentStreamProvider(payloadSigningEnabled: Boolean): ContentStreamProvider? {
    return when (this) {
        is OutgoingContent.NoContent -> null
        is OutgoingContent.ByteArrayContent -> {
            if (payloadSigningEnabled) ContentStreamProvider.fromByteArray(bytes()) else null
        }
        is OutgoingContent.ContentWrapper -> delegate().toContentStreamProvider(payloadSigningEnabled)
        else -> {
            if (payloadSigningEnabled) {
                error(
                    "AwsSigV4Plugin can sign only replayable ByteArrayContent payloads. " +
                        "Set payloadSigningEnabled=false for streaming content."
                )
            }
            null
        }
    }
}

private fun HttpRequestBuilder.applySignedRequest(signedRequest: SdkHttpRequest) {
    signedRequest.headers().forEach { (name, values) ->
        if (!name.equals(HttpHeaders.Host, ignoreCase = true)) {
            headers.remove(name)
            values.forEach { value -> headers.append(name, value) }
        }
    }

    url.parameters.clear()
    signedRequest.rawQueryParameters().forEach { (name, values) ->
        if (values.isNullOrEmpty()) {
            url.parameters.append(name, "")
        } else {
            values.forEach { value -> url.parameters.append(name, value.orEmpty()) }
        }
    }
}

private fun AwsSigV4AuthLocation.toAwsAuthLocation(): AwsV4FamilyHttpSigner.AuthLocation =
    when (this) {
        AwsSigV4AuthLocation.Header -> AwsV4FamilyHttpSigner.AuthLocation.HEADER
        AwsSigV4AuthLocation.QueryString -> AwsV4FamilyHttpSigner.AuthLocation.QUERY_STRING
    }

private object EmptyAwsContent : OutgoingContent.NoContent()
