package io.bluetape4k.aws.s3.model

import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.ObjectIdentifier

/**
 * [Delete]를 빌더로 생성합니다.
 *
 * 예제:
 * ```kotlin
 * val result = delete { quiet(true) }
 * // result.quiet() == true
 * ```
 */
inline fun delete(
    builder: Delete.Builder.() -> Unit,
): Delete =
    Delete.builder().apply(builder).build()

/**
 * [ObjectIdentifier] 배열로 [Delete]를 생성합니다.
 *
 * 예제:
 * ```kotlin
 * val first = objectIdentifier("images/a.png")
 * val second = objectIdentifier("images/b.png")
 * val result = deleteOf(first, second, quiet = true)
 * // result.objects().size == 2
 * ```
 */
@JvmName("deleteOfObjectsArray")
inline fun deleteOf(
    vararg objectIds: ObjectIdentifier,
    quiet: Boolean = false,
    builder: Delete.Builder.() -> Unit = {},
): Delete =
    delete {
        objects(objectIds.toList())
        quiet(quiet)
        builder()
    }

/** Java 바이너리 호환성을 위해 builder 없는 배열 오버로드를 유지합니다. */
@JvmName("deleteOfObjectsArray")
internal fun deleteOfObjectsArrayLegacy(
    vararg objectIds: ObjectIdentifier,
    quiet: Boolean = false,
): Delete = delete {
    objects(objectIds.toList())
    quiet(quiet)
}

/**
 * [ObjectIdentifier] 컬렉션으로 [Delete]를 생성합니다.
 *
 * 예제:
 * ```kotlin
 * val ids = listOf(objectIdentifier("a.txt"), objectIdentifier("b.txt"))
 * val result = deleteOf(ids)
 * // result.quiet() == false
 * ```
 */
@JvmName("deleteOfObjectsCollection")
inline fun deleteOf(
    objectIds: Collection<ObjectIdentifier>,
    quiet: Boolean = false,
    builder: Delete.Builder.() -> Unit = {},
): Delete =
    delete {
        objects(objectIds)
        quiet(quiet)
        builder()
    }

/** Java 바이너리 호환성을 위해 builder 없는 컬렉션 오버로드를 유지합니다. */
@JvmName("deleteOfObjectsCollection")
internal fun deleteOfObjectsCollectionLegacy(
    objectIds: Collection<ObjectIdentifier>,
    quiet: Boolean = false,
): Delete = delete {
    objects(objectIds)
    quiet(quiet)
}
