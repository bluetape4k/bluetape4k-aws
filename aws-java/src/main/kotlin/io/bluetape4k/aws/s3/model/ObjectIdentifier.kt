package io.bluetape4k.aws.s3.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.s3.model.ObjectIdentifier

/**
 * See the API documentation for details.
 *
 * See the API documentation for details.
 *
 * Example:
 * ```kotlin
 * val result = objectIdentifier("docs/readme.md")
 * // result.key() == "docs/readme.md"
 * ```
 */
inline fun objectIdentifier(
    key: String,
    builder: ObjectIdentifier.Builder.() -> Unit = {},
): ObjectIdentifier {
    key.requireNotBlank("key")
    return ObjectIdentifier.builder()
        .key(key)
        .apply(builder)
        .build()
}

/**
 * See the API documentation for details.
 *
 * Example:
 * ```kotlin
 * val result = objectIdentifierOf("docs/readme.md", versionId = "v3")
 * // result.versionId() == "v3"
 * ```
 */
inline fun objectIdentifierOf(
    key: String,
    versionId: String? = null,
    builder: ObjectIdentifier.Builder.() -> Unit = {},
): ObjectIdentifier =
    objectIdentifier(key) {
        versionId(versionId)
        builder()
    }
