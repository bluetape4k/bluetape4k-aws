package io.bluetape4k.aws.kotlin.s3

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.createBucket
import aws.sdk.kotlin.services.s3.listObjectsV2
import aws.sdk.kotlin.services.s3.listObjectVersions
import aws.sdk.kotlin.services.s3.model.CreateBucketRequest
import aws.sdk.kotlin.services.s3.model.CreateBucketResponse
import aws.sdk.kotlin.services.s3.model.DeleteBucketRequest
import aws.sdk.kotlin.services.s3.model.DeleteBucketResponse
import aws.sdk.kotlin.services.s3.model.DeleteMarkerEntry
import aws.sdk.kotlin.services.s3.model.DeleteObjectsResponse
import aws.sdk.kotlin.services.s3.model.HeadBucketRequest
import aws.sdk.kotlin.services.s3.model.ObjectIdentifier
import aws.sdk.kotlin.services.s3.model.ObjectVersion
import aws.smithy.kotlin.runtime.ServiceException
import aws.smithy.kotlin.runtime.http.response.statusCode
import io.bluetape4k.aws.kotlin.s3.model.deleteBucketRequestOf
import io.bluetape4k.aws.kotlin.s3.model.deleteOf
import io.bluetape4k.aws.kotlin.s3.model.deleteObjectsRequestOf
import io.bluetape4k.aws.kotlin.s3.model.headBucketRequestOf
import io.bluetape4k.aws.kotlin.s3.model.objectIdentifierOf
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotBlank
import kotlinx.coroutines.CancellationException

/**
 * [bucket]의 버킷이 존재하는지 확인합니다.
 *
 * 존재하지 않는 버킷(`NoSuchBucket`/`NotFound`/HTTP `404`)만 `false`로 정규화하고,
 * 인증 실패/네트워크 오류 등 다른 예외는 그대로 전파합니다.
 *
 * ```
 * val exists = s3Client.existsBucket("bucket-name")
 * ```
 *
 * @param bucket 버킷 이름
 * @return 버킷이 존재하면 `true`, 존재하지 않으면 `false`
 */
suspend inline fun S3Client.existsBucket(
    bucket: String,
    crossinline builder: HeadBucketRequest.Builder.() -> Unit = {},
): Boolean {
    val headBucketRequest = headBucketRequestOf(bucket, builder = builder)
    return try {
        headBucket(headBucketRequest)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (e.isMissingBucketError()) false else throw e
    }
}

/**
 * [bucketName]의 버킷을 생성합니다.
 * [builder] 를 통해 버킷 생성 설정을 변경할 수 있습니다.
 *
 * ```
 * s3Client.createBucket("bucket-name") {
 *    acl = BucketCannedACL.PRIVATE
 *    region = "us-west-2"
 *    createBucketConfiguration {
 *        locationConstraint = BucketLocationConstraint.US_WEST_2
 *    }
 * }
 * ```
 * @param bucketName 버킷 이름
 * @param builder [CreateBucketRequest.Builder] 를 통해 [CreateBucketRequest] 를 설정합니다.
 * @return [CreateBucketResponse] 인스턴스
 */
suspend inline fun S3Client.createBucket(
    bucketName: String,
    crossinline builder: CreateBucketRequest.Builder.() -> Unit = {},
): CreateBucketResponse {
    bucketName.requireNotBlank("bucketName")

    return createBucket {
        bucket = bucketName
        builder()
    }
}

/**
 * [bucketName]의 버킷이 존재하지 않으면 생성합니다.
 *
 * ```
 * s3Client.ensureBucket("bucket-name")
 * ```
 *
 * @param bucketName 버킷 이름
 */
suspend inline fun S3Client.ensureBucketExists(
    bucketName: String,
    crossinline builder: CreateBucketRequest.Builder.() -> Unit = {},
) {
    bucketName.requireNotBlank("bucketName")

    if (!existsBucket(bucketName)) {
        createBucket(bucketName, builder)
    }
}


/**
 * Deletes all current objects, object versions, delete markers, then deletes [bucket].
 *
 * Versioned and versioning-suspended buckets require deleting each object
 * version and delete marker by `versionId`; deleting only current object keys can
 * leave the bucket non-empty.
 *
 * @param bucket bucket name to delete
 * @return [DeleteBucketResponse] returned by S3
 */
suspend inline fun S3Client.forceDeleteBucket(
    bucket: String,
    crossinline builder: DeleteBucketRequest.Builder.() -> Unit = {},
): DeleteBucketResponse {
    bucket.requireNotBlank("bucket")

    deleteAllObjectVersions(bucket)
    deleteAllCurrentObjects(bucket)
    deleteAllObjectVersions(bucket)

    // 버킷 삭제
    log.debug { "버킷을 삭제합니다. bucket=$bucket" }
    return deleteBucket(deleteBucketRequestOf(bucket, builder = builder))
}

@PublishedApi
internal suspend fun S3Client.deleteAllCurrentObjects(bucket: String) {
    // 버킷 내 모든 Object 삭제 (listObjectsV2는 최대 1000개만 반환하므로, 모든 Object를 삭제하기 위해 반복)
    log.debug { "버킷의 모든 Object를 삭제합니다. bucket=$bucket" }
    do {
        val keys = listObjectsV2 { this.bucket = bucket }.contents?.mapNotNull { it.key } ?: emptyList()

        if (keys.isNotEmpty()) {
            deleteAll(bucket, keys).throwIfDeleteFailed(bucket)
        }
    } while (keys.isNotEmpty())
}

@PublishedApi
internal suspend fun S3Client.deleteAllObjectVersions(bucket: String) {
    var keyMarker: String? = null
    var versionIdMarker: String? = null

    do {
        val response = listObjectVersions {
            this.bucket = bucket
            this.keyMarker = keyMarker
            this.versionIdMarker = versionIdMarker
        }

        val identifiers = buildList {
            response.versions.orEmpty().forEach { addObjectVersion(it) }
            response.deleteMarkers.orEmpty().forEach { addDeleteMarker(it) }
        }

        if (identifiers.isNotEmpty()) {
            log.debug { "Delete object versions and delete markers in bucket=$bucket, size=${identifiers.size}" }
            deleteObjects(deleteObjectsRequestOf(bucket, deleteOf(identifiers, quiet = true)))
                .throwIfDeleteFailed(bucket)
        }

        val isTruncated = response.isTruncated == true
        check(!isTruncated || response.nextKeyMarker != null || response.nextVersionIdMarker != null) {
            "S3 listObjectVersions response for bucket=$bucket was truncated without pagination markers"
        }
        keyMarker = response.nextKeyMarker
        versionIdMarker = response.nextVersionIdMarker
    } while (isTruncated)
}

private fun MutableList<ObjectIdentifier>.addObjectVersion(version: ObjectVersion) {
    addVersionedIdentifier(version.key, version.versionId)
}

private fun MutableList<ObjectIdentifier>.addDeleteMarker(deleteMarker: DeleteMarkerEntry) {
    addVersionedIdentifier(deleteMarker.key, deleteMarker.versionId)
}

private fun MutableList<ObjectIdentifier>.addVersionedIdentifier(key: String?, versionId: String?) {
    if (!key.isNullOrBlank()) {
        add(objectIdentifierOf(key, versionId))
    }
}

private fun DeleteObjectsResponse.throwIfDeleteFailed(bucket: String) {
    val errors = errors.orEmpty()
    check(errors.isEmpty()) {
        val details = errors.joinToString { error ->
            "key=${error.key}, versionId=${error.versionId}, code=${error.code}, message=${error.message}"
        }
        "Failed to delete S3 objects in bucket=$bucket: $details"
    }
}

@PublishedApi
internal fun Throwable.isMissingBucketError(): Boolean {
    val serviceError = this as? ServiceException ?: return false
    val errorCode = serviceError.sdkErrorMetadata.errorCode
    val statusCode = serviceError.sdkErrorMetadata.protocolResponse.statusCode()?.value
    return errorCode in setOf("NoSuchBucket", "NotFound") || statusCode == 404
}
