package io.bluetape4k.aws.s3.model

import software.amazon.awssdk.services.s3.model.CopyObjectResult
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import java.io.Serializable

/**
 * See the API documentation for details.
 *
 * See the API documentation for details.
 * See the API documentation for details.
 * See the API documentation for details.
 *
 * ```kotlin
 * val result = s3Client.moveObject("bucket", "src/a.txt", "bucket", "archive/a.txt")
 * // result.isSuccess == true
 * // result.isPartialSuccess == false
 * ```
 */
data class MoveObjectResult(
    val copyResult: CopyObjectResult,
    val deleteResponse: DeleteObjectResponse? = null,
): Serializable {
    /**
     * See the API documentation for details.
     */
    val isSuccess: Boolean
        get() = copyResult.eTag()?.isNotBlank() == true && deleteResponse != null

    /**
     * See the API documentation for details.
     */
    val isPartialSuccess: Boolean
        get() = copyResult.eTag()?.isNotBlank() == true && deleteResponse == null

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
