package io.bluetape4k.aws.kotlin.s3.model

import aws.sdk.kotlin.services.s3.model.Delete
import aws.sdk.kotlin.services.s3.model.ObjectIdentifier
import io.bluetape4k.support.requireNotEmpty

/**
 * Creates a [Delete] request for S3 objects.
 *
 * ```kotlin
 * val delete = deleteOf("key-1", "key-2")
 * ```
 *
 * @param quiet whether to return a summarized deletion result
 * @param keys keys of the objects to delete
 * @return the [Delete]
 */
@JvmName("deleteOfArray")
inline fun deleteOf(
    vararg keys: String,
    quiet: Boolean? = null,
    crossinline builder: Delete.Builder.() -> Unit = {},
): Delete =
    deleteOf(keys.map { it.toObjectIdentifier() }, quiet, builder)

/**
 * Creates a [Delete] request for S3 objects.
 *
 * ```kotlin
 * val delete = deleteOf(listOf("key-1", "key-2"))
 * ```
 *
 * @param quiet whether to return a summarized deletion result
 * @param keys keys of the objects to delete
 * @return the [Delete]
 */
@JvmName("deleteOfCollection")
inline fun deleteOf(
    keys: Collection<String>,
    quiet: Boolean? = null,
    crossinline builder: Delete.Builder.() -> Unit = {},
): Delete =
    deleteOf(keys.map { it.toObjectIdentifier() }, quiet, builder)

/**
 * Creates a [Delete] request for S3 objects.
 *
 * ```kotlin
 * val deleteKeys = listOf("key-1", "key-2").map { it.toObjectIdentifier() }
 * val delete = deleteOf(deleteKeys, quiet = true)
 * ```
 *
 * @param quiet whether to return a summarized deletion result
 * @param keys keys of the objects to delete
 * @return the [Delete]
 */
@JvmName("deleteOfObjectIdentifierCollection")
inline fun deleteOf(
    keys: Collection<ObjectIdentifier>,
    quiet: Boolean? = null,
    crossinline builder: Delete.Builder.() -> Unit = {},
): Delete {
    keys.requireNotEmpty("keys")

    return Delete {
        this.objects = keys.toList()
        this.quiet = quiet

        builder()
    }
}

/**
 * Creates a [Delete] request for S3 objects.
 *
 * ```kotlin
 * val id1 = "key-1".toObjectIdentifier()
 * val id2 = "key-2".toObjectIdentifier()
 * val delete = deleteOf(id1, id2, quiet = false)
 * ```
 *
 * @param quiet whether to return a summarized deletion result
 * @param keys keys of the objects to delete
 * @return the [Delete]
 */
@JvmName("deleteOfObjectIdentifierArray")
inline fun deleteOf(
    vararg keys: ObjectIdentifier,
    quiet: Boolean? = null,
    crossinline builder: Delete.Builder.() -> Unit = {},
): Delete =
    deleteOf(keys.toList(), quiet, builder)
