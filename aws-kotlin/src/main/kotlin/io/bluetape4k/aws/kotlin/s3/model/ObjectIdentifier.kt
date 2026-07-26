package io.bluetape4k.aws.kotlin.s3.model

import aws.sdk.kotlin.services.s3.model.ObjectIdentifier
import io.bluetape4k.support.requireNotBlank

/**
 * Creates an [ObjectIdentifier] whose object key is [key].
 *
 * ```kotlin
 * val identifier = objectIdentifierOf("key")
 * ```
 *
 * @param key object identifier key
 * @param versionId object identifier version ID
 *
 * @return the [ObjectIdentifier]
 */
inline fun objectIdentifierOf(
    key: String,
    versionId: String? = null,
    crossinline builder: ObjectIdentifier.Builder.() -> Unit = {},
): ObjectIdentifier {
    key.requireNotBlank("key")

    return ObjectIdentifier {
        this.key = key
        this.versionId = versionId

        builder()
    }
}

/**
 * Creates an [ObjectIdentifier] using this string as the object key.
 *
 * ```kotlin
 * val identifier = "key".toObjectIdentifier()
 * ```
 *
 * @receiver object identifier key
 * @param versionId object identifier version ID
 *
 * @return the [ObjectIdentifier]
 */
inline fun String.toObjectIdentifier(
    versionId: String? = null,
    crossinline builder: ObjectIdentifier.Builder.() -> Unit = {},
): ObjectIdentifier {
    return objectIdentifierOf(this, versionId, builder)
}
