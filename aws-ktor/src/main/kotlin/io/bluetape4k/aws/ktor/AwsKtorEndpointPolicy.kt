package io.bluetape4k.aws.ktor

import java.net.URI
import java.util.Locale

/** Validates the final AWS Kotlin SDK endpoint after every client customizer has run. */
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
