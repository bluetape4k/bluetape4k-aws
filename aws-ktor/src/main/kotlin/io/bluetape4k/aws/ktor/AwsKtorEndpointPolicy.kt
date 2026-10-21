package io.bluetape4k.aws.ktor

import aws.smithy.kotlin.runtime.client.ProtocolRequestInterceptorContext
import aws.smithy.kotlin.runtime.client.RequestInterceptorContext
import aws.smithy.kotlin.runtime.client.endpoints.Endpoint
import aws.smithy.kotlin.runtime.client.endpoints.EndpointProvider
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
    val uri = parseAwsKotlinServiceEndpoint(endpoint) ?: return
    require(!region.isNullOrBlank()) {
        "region must be configured when endpointOverride is configured."
    }

    validateAwsKotlinEndpointScheme(uri, allowInsecureLocalHttpEndpoint)
}

internal fun <P, T : EndpointProvider<P>> checkedAwsKotlinEndpointProvider(
    provider: T,
    region: (P) -> String?,
    allowInsecureLocalHttpEndpoint: Boolean,
    create: (suspend (P) -> Endpoint) -> T,
): T = create { parameters ->
    provider.resolveEndpoint(parameters).also { endpoint ->
        validateAwsKotlinServiceEndpoint(
            endpoint = endpoint.uri.toString(),
            region = region(parameters),
            allowInsecureLocalHttpEndpoint = allowInsecureLocalHttpEndpoint,
        )
    }
}

/** Rejects custom endpoint providers before a service client can resolve credentials. */
internal fun requireAwsKotlinEndpointProviderUnset(provider: EndpointProvider<*>?) {
    require(provider == null) {
        "Custom AWS endpoint providers are not supported by Ktor service clients; configure endpointUrl instead."
    }
}

private fun validateAwsKotlinRequestEndpoint(
    endpoint: String,
    allowInsecureLocalHttpEndpoint: Boolean,
) {
    val uri = parseAwsKotlinServiceEndpoint(endpoint) ?: return
    validateAwsKotlinEndpointScheme(uri, allowInsecureLocalHttpEndpoint)
}

private fun parseAwsKotlinServiceEndpoint(endpoint: String?): URI? {
    if (endpoint == null) return null

    val uri = try {
        URI(endpoint)
    } catch (cause: Exception) {
        throw IllegalArgumentException("AWS service endpoint must be an absolute HTTP(S) URL.", cause)
    }

    require(uri.isAbsolute && uri.host != null && uri.rawUserInfo == null) {
        "AWS service endpoint must be an absolute HTTP(S) URL without user information."
    }
    return uri
}

private fun validateAwsKotlinEndpointScheme(uri: URI, allowInsecureLocalHttpEndpoint: Boolean) {
    val scheme = uri.scheme.lowercase(Locale.ROOT)
    if (scheme == "https") return

    require(scheme == "http" && allowInsecureLocalHttpEndpoint && isLoopbackLiteral(uri.host)) {
        "AWS service endpoint must use HTTPS; HTTP is allowed only for opted-in loopback endpoints."
    }
}

internal class AwsKotlinEndpointPolicyInterceptor(
    private val configuredEndpoint: String?,
    private val region: String?,
    private val allowInsecureLocalHttpEndpoint: Boolean,
) : HttpInterceptor {

    override fun readBeforeExecution(context: RequestInterceptorContext<Any>) {
        validateAwsKotlinServiceEndpoint(
            endpoint = configuredEndpoint,
            region = region,
            allowInsecureLocalHttpEndpoint = allowInsecureLocalHttpEndpoint,
        )
    }

    override suspend fun modifyBeforeSigning(
        context: ProtocolRequestInterceptorContext<Any, HttpRequest>,
    ): HttpRequest {
        val request = context.protocolRequest
        validateAwsKotlinRequestEndpoint(
            endpoint = request.url.toString(),
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
