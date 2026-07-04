package io.bluetape4k.aws.ktor.client

/**
 * Location where AWS SigV4 authentication data is added to a request.
 *
 * ## Behavior/Contract
 * - [Header] adds signing headers such as `Authorization` and `X-Amz-Date`.
 * - [QueryString] adds `X-Amz-*` query parameters for presigned URL style authentication.
 *
 * ```kotlin
 * install(AwsSigV4Plugin) {
 *     authLocation = AwsSigV4AuthLocation.Header
 * }
 * ```
 */
enum class AwsSigV4AuthLocation {
    /**
     * Sends signing information through headers such as `Authorization`, `X-Amz-Date`, and `X-Amz-Security-Token`.
     */
    Header,

    /**
     * Sends signing information through `X-Amz-*` query parameters.
     *
     * Use when the request URL itself must contain authentication information, as with presigned URLs.
     */
    QueryString,
}
