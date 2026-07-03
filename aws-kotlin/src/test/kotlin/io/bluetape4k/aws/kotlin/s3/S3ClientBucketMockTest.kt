package io.bluetape4k.aws.kotlin.s3

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.DeleteBucketResponse
import aws.sdk.kotlin.services.s3.model.DeleteMarkerEntry
import aws.sdk.kotlin.services.s3.model.DeleteObjectsRequest
import aws.sdk.kotlin.services.s3.model.DeleteObjectsResponse
import aws.sdk.kotlin.services.s3.model.Error as S3DeleteError
import aws.sdk.kotlin.services.s3.model.GetBucketPolicyRequest
import aws.sdk.kotlin.services.s3.model.GetBucketPolicyResponse
import aws.sdk.kotlin.services.s3.model.ListObjectVersionsRequest
import aws.sdk.kotlin.services.s3.model.ListObjectVersionsResponse
import aws.sdk.kotlin.services.s3.model.ListObjectsV2Response
import aws.sdk.kotlin.services.s3.model.ObjectVersion
import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.ServiceErrorMetadata
import aws.smithy.kotlin.runtime.ServiceException
import aws.smithy.kotlin.runtime.http.HttpStatusCode
import aws.smithy.kotlin.runtime.http.response.HttpResponse
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class S3ClientBucketMockTest {

    private val client = mockk<S3Client>()

    @BeforeEach
    fun setup() {
        clearMocks(client)
    }

    @Test
    fun `forceDeleteBucket deletes object versions and delete markers`() = runSuspendIO {
        val bucket = "versioned-bucket"
        val listVersionRequests = mutableListOf<ListObjectVersionsRequest>()
        val deleteRequests = mutableListOf<DeleteObjectsRequest>()

        coEvery { client.listObjectVersions(capture(listVersionRequests)) } returnsMany listOf(
            ListObjectVersionsResponse {
                versions = listOf(
                    ObjectVersion {
                        key = "reports/current.csv"
                        versionId = "version-2"
                    },
                )
                isTruncated = true
                nextKeyMarker = "reports/current.csv"
                nextVersionIdMarker = "version-2"
            },
            ListObjectVersionsResponse {
                versions = listOf(
                    ObjectVersion {
                        key = "reports/current.csv"
                        versionId = "version-1"
                    },
                )
                deleteMarkers = listOf(
                    DeleteMarkerEntry {
                        key = "reports/removed.csv"
                        versionId = "delete-marker-1"
                    },
                )
                isTruncated = false
            },
            ListObjectVersionsResponse {
                isTruncated = false
            },
        )
        coEvery { client.deleteObjects(capture(deleteRequests)) } returns DeleteObjectsResponse {}
        coEvery { client.listObjectsV2(any()) } returns ListObjectsV2Response {
            contents = emptyList()
        }
        coEvery { client.deleteBucket(any()) } returns DeleteBucketResponse {}

        client.forceDeleteBucket(bucket)

        listVersionRequests shouldHaveSize 3
        listVersionRequests.first().keyMarker.shouldBeNull()
        listVersionRequests.first().versionIdMarker.shouldBeNull()
        listVersionRequests[1].keyMarker shouldBeEqualTo "reports/current.csv"
        listVersionRequests[1].versionIdMarker shouldBeEqualTo "version-2"
        listVersionRequests.last().keyMarker.shouldBeNull()
        listVersionRequests.last().versionIdMarker.shouldBeNull()

        deleteRequests shouldHaveSize 2
        deleteRequests.forEach { it.bucket shouldBeEqualTo bucket }

        val objects = deleteRequests.flatMap { it.delete?.objects.shouldNotBeNull() }
        objects shouldHaveSize 3
        objects.map { it.key to it.versionId }.toSet() shouldBeEqualTo setOf(
            "reports/current.csv" to "version-2",
            "reports/current.csv" to "version-1",
            "reports/removed.csv" to "delete-marker-1",
        )

        coVerify(exactly = 3) { client.listObjectVersions(any()) }
        coVerify(exactly = 2) { client.deleteObjects(any()) }
        coVerify(exactly = 1) { client.deleteBucket(any()) }
    }

    @Test
    fun `forceDeleteBucket fails when version delete reports errors`() = runSuspendIO {
        val bucket = "versioned-bucket"

        coEvery { client.listObjectVersions(any()) } returns ListObjectVersionsResponse {
            versions = listOf(
                ObjectVersion {
                    key = "locked.csv"
                    versionId = "version-1"
                },
            )
            isTruncated = false
        }
        coEvery { client.deleteObjects(any()) } returns DeleteObjectsResponse {
            errors = listOf(
                S3DeleteError {
                    key = "locked.csv"
                    versionId = "version-1"
                    code = "AccessDenied"
                    message = "object is locked"
                },
            )
        }

        assertFailsWith<IllegalStateException> {
            client.forceDeleteBucket(bucket)
        }

        coVerify(exactly = 1) { client.listObjectVersions(any()) }
        coVerify(exactly = 1) { client.deleteObjects(any()) }
        coVerify(exactly = 0) { client.deleteBucket(any()) }
    }

    @Test
    fun `tryGetBucketPolicy returns null only for missing policy errors`() = runSuspendIO {
        coEvery { client.getBucketPolicy(any<GetBucketPolicyRequest>()) } throws
                serviceException(errorCode = "NoSuchBucketPolicy", statusCode = 404)

        val result = client.tryGetBucketPolicy("bucket-without-policy")

        result.shouldBeNull()
        coVerify(exactly = 1) { client.getBucketPolicy(any<GetBucketPolicyRequest>()) }
    }

    @Test
    fun `tryGetBucketPolicy propagates access denied errors`() = runSuspendIO {
        coEvery { client.getBucketPolicy(any<GetBucketPolicyRequest>()) } throws
                serviceException(errorCode = "AccessDenied", statusCode = 403)

        assertFailsWith<ServiceException> {
            client.tryGetBucketPolicy("private-bucket")
        }

        coVerify(exactly = 1) { client.getBucketPolicy(any<GetBucketPolicyRequest>()) }
    }

    @Test
    fun `tryGetBucketPolicy returns policy when request succeeds`() = runSuspendIO {
        coEvery { client.getBucketPolicy(any<GetBucketPolicyRequest>()) } returns GetBucketPolicyResponse {
            policy = """{"Version":"2012-10-17","Statement":[]}"""
        }

        val result = client.tryGetBucketPolicy("bucket-with-policy")

        result shouldBeEqualTo """{"Version":"2012-10-17","Statement":[]}"""
        coVerify(exactly = 1) { client.getBucketPolicy(any<GetBucketPolicyRequest>()) }
    }

    @OptIn(InternalApi::class)
    private fun serviceException(
        errorCode: String,
        statusCode: Int,
    ): ServiceException {
        val exception = ServiceException("test error")
        exception.sdkErrorMetadata.attributes[ServiceErrorMetadata.ErrorCode] = errorCode
        exception.sdkErrorMetadata.attributes[ServiceErrorMetadata.ProtocolResponse] =
            HttpResponse(status = HttpStatusCode.fromValue(statusCode))
        return exception
    }
}
