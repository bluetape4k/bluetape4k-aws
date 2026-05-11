package io.bluetape4k.aws.ktor.client

/**
 * AWS SigV4 인증 데이터를 요청에 넣을 위치입니다.
 *
 * ## 동작/계약
 * - [Header]는 `Authorization`, `X-Amz-Date` 같은 서명 헤더를 추가한다.
 * - [QueryString]은 presigned URL 형태의 `X-Amz-*` 쿼리 파라미터를 추가한다.
 *
 * ```kotlin
 * install(AwsSigV4Plugin) {
 *     authLocation = AwsSigV4AuthLocation.Header
 * }
 * ```
 */
enum class AwsSigV4AuthLocation {
    /**
     * `Authorization`, `X-Amz-Date`, `X-Amz-Security-Token` 같은 헤더로 서명 정보를 보냅니다.
     */
    Header,

    /**
     * `X-Amz-*` query parameter로 서명 정보를 보냅니다.
     *
     * Presigned URL처럼 요청 URL 자체가 인증 정보를 포함해야 할 때 사용합니다.
     */
    QueryString,
}
