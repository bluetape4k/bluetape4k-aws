package io.bluetape4k.aws.spring.config

import org.springframework.boot.context.config.ConfigDataResource
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Spring Boot ConfigData SPI가 전달하는 AWS resource carrier입니다.
 *
 * 이 타입은 resolver와 loader 사이의 내부 계약이며 소비자 확장을 위한 public
 * API가 아닙니다. 원격 source와 bound properties는 identity나 문자열 표현에
 * 포함하지 않고 opaque digest만 노출합니다.
 */
class AwsConfigDataResource private constructor(
    internal val location: AwsConfigDataLocation,
    internal val boundProperties: Any?,
    internal val disabled: Boolean,
) : ConfigDataResource(location.optional) {

    internal val isOptionalResource: Boolean
        get() = location.optional

    internal val isDisabled: Boolean
        get() = disabled

    internal val backendKey: String
        get() = location.backend.key

    internal val opaqueIdentity: String
        get() = "bluetape4k.aws.configdata.${location.backend.key}.${digest(location)}"

    override fun equals(other: Any?): Boolean =
        other is AwsConfigDataResource && location == other.location

    override fun hashCode(): Int = location.hashCode()

    override fun toString(): String {
        val queryKeys = location.options.keys.sorted().joinToString("&")
        return if (queryKeys.isEmpty()) opaqueIdentity else "$opaqueIdentity?$queryKeys"
    }

    internal companion object {
        fun from(
            location: AwsConfigDataLocation,
            boundProperties: Any? = null,
            disabled: Boolean = false,
        ): AwsConfigDataResource = AwsConfigDataResource(location, boundProperties, disabled)

        private fun digest(location: AwsConfigDataLocation): String {
            val input = "${location.backend.key}\u0000${location.optional}\u0000${location.canonicalDecodedLocation}"
            return MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(StandardCharsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
                .take(DIGEST_LENGTH)
        }

        private const val DIGEST_LENGTH = 12
    }
}
