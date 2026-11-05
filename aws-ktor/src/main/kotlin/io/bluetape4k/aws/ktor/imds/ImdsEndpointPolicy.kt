package io.bluetape4k.aws.ktor.imds

import aws.sdk.kotlin.runtime.config.imds.EndpointMode
import java.net.URI
import java.util.*

/** Applies the IMDS-specific endpoint allowlist before a client or request is created. */
internal object ImdsEndpointPolicy {

    private val IPV4_ENDPOINT = URI("http://169.254.169.254")
    private val IPV6_ENDPOINT = URI("http://[fd00:ec2::254]")
    private const val IPV4_OCTET_COUNT = 4
    private const val LOOPBACK_IPV4_PREFIX = "127"
    private const val MAX_IPV4_OCTET = 255

    fun resolve(
        endpoint: URI?,
        endpointMode: EndpointMode,
        allowInsecureLocalHttpEndpoint: Boolean,
    ): URI {
        val resolved = endpoint ?: when (endpointMode) {
            EndpointMode.IPv4 -> IPV4_ENDPOINT
            EndpointMode.IPv6 -> IPV6_ENDPOINT
        }
        validate(resolved, allowInsecureLocalHttpEndpoint)
        return resolved
    }

    private fun validate(endpoint: URI, allowInsecureLocalHttpEndpoint: Boolean) {
        require(endpoint.isAbsolute && endpoint.host != null && endpoint.rawUserInfo == null) {
            "IMDS endpoint must be an absolute HTTP(S) URL without user information."
        }
        require(endpoint.rawQuery == null && endpoint.rawFragment == null) {
            "IMDS endpoint must not contain a query or fragment."
        }
        require(endpoint.path.isNullOrEmpty() || endpoint.path == "/") {
            "IMDS endpoint must be a base URL without a path."
        }

        val scheme = endpoint.scheme.lowercase(Locale.ROOT)
        if (scheme == "https") return

        val isAwsLinkLocal = isAwsDefaultHttpEndpoint(endpoint)
        val isOptedInLoopback = allowInsecureLocalHttpEndpoint && isLoopback(endpoint.host)
        require(scheme == "http" && (isAwsLinkLocal || isOptedInLoopback)) {
            "IMDS endpoint must use HTTPS; HTTP is allowed only for AWS default or opted-in loopback endpoints."
        }
    }

    private fun isAwsDefaultHttpEndpoint(endpoint: URI): Boolean {
        val host = endpoint.host.removeBrackets().lowercase(Locale.ROOT)
        val defaultPort = endpoint.port == -1 || endpoint.port == 80
        return defaultPort && (host == "169.254.169.254" || host == "fd00:ec2::254")
    }

    private fun isLoopback(hostValue: String): Boolean {
        val host = hostValue.removeBrackets().lowercase(Locale.ROOT)
        if (host == "localhost" || host == "::1") return true

        val octets = host.split('.')
        return octets.size == IPV4_OCTET_COUNT && octets.first() == LOOPBACK_IPV4_PREFIX && octets.all { octet ->
            octet.isNotEmpty() &&
                    (octet.length == 1 || octet.first() != '0') &&
                    octet.all(Char::isDigit) &&
                    octet.toIntOrNull()?.let { it <= MAX_IPV4_OCTET } == true
        }
    }

    private fun String.removeBrackets(): String = removePrefix("[").removeSuffix("]")
}
