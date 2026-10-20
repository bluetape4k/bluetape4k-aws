package io.bluetape4k.aws.ktor

import aws.smithy.kotlin.runtime.client.ProtocolRequestInterceptorContext
import aws.smithy.kotlin.runtime.http.interceptors.HttpInterceptor
import aws.smithy.kotlin.runtime.http.request.HttpRequest
import java.net.URI
import java.util.Locale

/** AWS 서비스 요청에 사용할 최종 endpoint URL을 검사합니다. */
internal fun validateAwsKotlinServiceEndpoint(
    endpoint: String?,
    region: String?,
    allowInsecureLocalHttpEndpoint: Boolean,
) {
    if (endpoint == null) return

    val uri = try {
        URI(endpoint)
    } catch (cause: Exception) {
        throw IllegalArgumentException("AWS service endpoint must be an absolute HTTP(S) URL.", cause)
    }

    require(uri.isAbsolute && uri.host != null && uri.rawUserInfo == null) {
        "AWS service endpoint must be an absolute HTTP(S) URL without user information."
    }
    require(!region.isNullOrBlank()) {
        "region must be configured when endpointOverride is configured."
    }

    val scheme = uri.scheme.lowercase(Locale.ROOT)
    if (scheme == "https") return

    require(scheme == "http" && allowInsecureLocalHttpEndpoint && isLoopbackLiteral(uri.host)) {
        "AWS service endpoint must use HTTPS; HTTP is allowed only for opted-in loopback endpoints."
    }
}

internal class AwsKotlinEndpointPolicyInterceptor(
    private val region: String,
    private val allowInsecureLocalHttpEndpoint: Boolean,
) : HttpInterceptor {

    override suspend fun modifyBeforeTransmit(
        context: ProtocolRequestInterceptorContext<Any, HttpRequest>,
    ): HttpRequest {
        val request = context.protocolRequest
        validateAwsKotlinServiceEndpoint(
            endpoint = request.url.toString(),
            region = region,
            allowInsecureLocalHttpEndpoint = allowInsecureLocalHttpEndpoint,
        )
        return request
    }
}

private fun isLoopbackLiteral(host: String): Boolean {
    val normalizedHost = host.removePrefix("[").removeSuffix("]")
    if (normalizedHost.equals("localhost", ignoreCase = true) || normalizedHost == "::1") return true

    val octets = normalizedHost.split('.')
    if (octets.size != 4 || octets.first() != "127") return false

    return octets.all { octet ->
        octet.isNotEmpty() &&
            (octet.length == 1 || octet.first() != '0') &&
            octet.all { it in '0'..'9' } &&
            octet.toIntOrNull()?.let { it <= 255 } == true
    }
}
