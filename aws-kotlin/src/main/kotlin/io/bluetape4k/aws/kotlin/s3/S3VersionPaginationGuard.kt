package io.bluetape4k.aws.kotlin.s3

import aws.sdk.kotlin.services.s3.model.ListObjectVersionsResponse
import io.bluetape4k.aws.kotlin.PaginationFailure
import io.bluetape4k.aws.kotlin.PaginationGuard

/**
 * S3 object version pagination을 최대 10,000 page까지 추적합니다.
 *
 * marker는 key와 version ID의 쌍으로 비교합니다. truncated response에 marker가 없거나 marker 쌍이
 * 반복되거나 page 상한을 넘으면 [IllegalStateException]을 발생시킵니다. 예외 메시지에는 bucket
 * 이름과 원본 marker를 포함하지 않습니다.
 */
internal class S3VersionPaginationGuard {
    private val delegate = PaginationGuard<Marker>(MAX_PAGES, ::paginationException)

    fun nextMarkerOrNull(response: ListObjectVersionsResponse): Marker? {
        val marker = if (response.nextKeyMarker != null || response.nextVersionIdMarker != null) {
            Marker(response.nextKeyMarker, response.nextVersionIdMarker)
        } else {
            null
        }
        return delegate.nextTokenOrNull(
            hasNext = response.isTruncated == true,
            nextToken = marker,
        )
    }

    internal data class Marker(
        val keyMarker: String?,
        val versionIdMarker: String?,
    )

    private companion object {
        private const val MAX_PAGES = 10_000

        private fun paginationException(failure: PaginationFailure): IllegalStateException =
            IllegalStateException(
                when (failure) {
                    PaginationFailure.MISSING_TOKEN ->
                        "S3 listObjectVersions returned a truncated page without pagination markers"

                    PaginationFailure.REPEATED_TOKEN ->
                        "S3 listObjectVersions returned a non-progressing pagination marker"

                    PaginationFailure.PAGE_LIMIT_EXCEEDED ->
                        "S3 listObjectVersions pagination exceeded the configured page limit"
                },
            )
    }
}
