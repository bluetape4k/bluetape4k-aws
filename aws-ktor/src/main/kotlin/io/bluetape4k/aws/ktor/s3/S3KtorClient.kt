package io.bluetape4k.aws.ktor.s3

import io.bluetape4k.aws.ktor.client.AwsSigV4AuthLocation
import io.bluetape4k.aws.ktor.client.AwsSigV4Plugin
import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorHttpClientCustomizer
import io.bluetape4k.support.requireNotBlank
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.URLBuilder
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.takeFrom
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.http.SdkHttpFullRequest
import software.amazon.awssdk.http.SdkHttpMethod
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner
import software.amazon.awssdk.http.auth.spi.signer.HttpSigner
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest
import java.net.URI
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration

private const val S3_SERVICE = "s3"
private const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"
private const val S3_METADATA_PREFIX = "x-amz-meta-"
private val MIN_PRESIGN_EXPIRY: Duration = Duration.ofSeconds(1)
private val MAX_PRESIGN_EXPIRY: Duration = Duration.ofDays(7)

/**
 * Ktor `HttpClient`-based S3 REST client.
 *
 * ## Behavior / Contract
 *
 * Uses Ktor `HttpClient` and [AwsSigV4Plugin] to call the S3 REST API. Supports object upload,
 * download, delete, ListObjectsV2, multipart upload, and presigned GET/PUT URL generation.
 * Path-style URLs are used when `endpointOverride` is set; virtual-hosted URLs are used for
 * DNS-safe buckets on the default AWS S3 endpoint.
 *
 * **Ownership semantics**: An externally injected `HttpClient` is never closed by this client.
 * An `HttpClient` created by [s3KtorClientOf] is closed when `closeClient = true`. Similarly,
 * an `AwsCredentialsProvider` created by [s3KtorClientOf] (i.e. when the caller omits
 * `credentialsProvider`) is closed on [close] if it implements `AutoCloseable`. A
 * caller-supplied provider is never closed by this client.
 *
 * ```kotlin
 * import io.bluetape4k.aws.ktor.s3.s3KtorClientOf
 *
 * suspend fun roundTrip() {
 *     s3KtorClientOf(region = "ap-northeast-2").use { s3 ->
 *         s3.putObject("demo-bucket", "docs/hello.txt", "hello".encodeToByteArray())
 *         val bytes = s3.getObjectBytes("demo-bucket", "docs/hello.txt")
 *         check(bytes.decodeToString() == "hello")
 *     }
 * }
 * ```
 */
class S3KtorClient(
    private val httpClient: HttpClient,
    private val region: String,
    private val credentialsProvider: AwsCredentialsProvider,
    private val endpointOverride: Url? = null,
    private val addressingStyle: S3KtorAddressingStyle = S3KtorAddressingStyle.VirtualHosted,
    private val signingClock: Clock? = null,
    private val closeClient: Boolean = false,
    private val closeCredentialsProvider: Boolean = false,
    private val signer: AwsV4HttpSigner = AwsV4HttpSigner.create(),
): AutoCloseable {

    init {
        region.requireNotBlank("region")
    }

    /**
     * [bytes]를 S3 객체로 저장합니다.
     *
     * [metadata]의 key는 `x-amz-meta-` 접두사 없이 전달합니다.
     */
    suspend fun putObject(
        bucket: String,
        key: String,
        bytes: ByteArray,
        contentType: String? = null,
        metadata: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): S3KtorPutObjectResponse =
        putObject(
            request = S3KtorPutObjectRequest(bucket, key, contentType, metadata, headers),
            body = ByteArrayContent(bytes, contentType?.let(ContentType::parse)),
        )

    /**
     * Stores [bytes] while detecting content type from [key] and the payload.
     *
     * Use this helper for object names where the caller has no trusted HTTP
     * `Content-Type` header. Detection falls back to `application/octet-stream`.
     */
    suspend fun putObjectDetectingContentType(
        bucket: String,
        key: String,
        bytes: ByteArray,
        detector: S3KtorContentTypeDetector = S3KtorContentTypes.Default,
        metadata: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): S3KtorPutObjectResponse =
        putObject(
            bucket = bucket,
            key = key,
            bytes = bytes,
            contentType = S3KtorContentTypes.orFallback(detector.detect(key, bytes)),
            metadata = metadata,
            headers = headers,
        )

    /**
     * Stores [bytes] with S3 server-side encryption request headers.
     *
     * This helper only renders S3 request headers. Client-side envelope
     * encryption is provided by [S3KtorClientSideEncryption].
     */
    suspend fun putEncryptedObject(
        bucket: String,
        key: String,
        bytes: ByteArray,
        encryption: S3KtorServerSideEncryption,
        contentType: String? = null,
        metadata: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): S3KtorPutObjectResponse =
        putObject(
            bucket = bucket,
            key = key,
            bytes = bytes,
            contentType = contentType,
            metadata = metadata,
            headers = headers + encryption.headers(),
        )

    /**
     * [body]를 S3 객체로 저장합니다.
     *
     * Streaming body를 전달할 때는 caller가 정확한 body semantics를 책임집니다.
     */
    suspend fun putObject(
        request: S3KtorPutObjectRequest,
        body: OutgoingContent,
    ): S3KtorPutObjectResponse {
        val response = httpClient.put(objectUrl(request.bucket, request.key)) {
            applyPutHeaders(request)
            setBody(body)
        }.ensureSuccess()

        return S3KtorPutObjectResponse(
            eTag = response.headers[HttpHeaders.ETag],
            versionId = response.headers["x-amz-version-id"],
            headers = response.headers,
        )
    }

    /**
     * S3 객체를 byte array로 가져옵니다.
     */
    suspend fun getObjectBytes(bucket: String, key: String): ByteArray =
        getObject(bucket, key).bytes

    /**
     * S3 객체를 byte array와 metadata로 가져옵니다.
     */
    suspend fun getObject(bucket: String, key: String): S3KtorGetObjectResponse {
        val response = httpClient.get(objectUrl(bucket, key)).ensureSuccess()
        val bytes = response.bodyAsBytes()
        return S3KtorGetObjectResponse(
            bytes = bytes,
            eTag = response.headers[HttpHeaders.ETag],
            contentType = response.headers[HttpHeaders.ContentType],
            contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
            metadata = response.s3Metadata(),
            headers = response.headers,
        )
    }

    /**
     * S3 객체를 streaming channel로 가져옵니다.
     *
     * 반환된 [S3KtorStreamingObjectResponse.body]는 response channel이므로 caller가 소비를 완료해야 합니다.
     */
    suspend fun getObjectStream(bucket: String, key: String): S3KtorStreamingObjectResponse {
        val response = httpClient.get(objectUrl(bucket, key)).ensureSuccess()
        return S3KtorStreamingObjectResponse(
            body = response.bodyAsChannel(),
            eTag = response.headers[HttpHeaders.ETag],
            contentType = response.headers[HttpHeaders.ContentType],
            contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
            metadata = response.s3Metadata(),
            headers = response.headers,
        )
    }

    /**
     * S3 객체를 삭제합니다.
     */
    suspend fun deleteObject(bucket: String, key: String): S3KtorDeleteObjectResponse {
        val response = httpClient.delete(objectUrl(bucket, key)).ensureSuccess()
        return S3KtorDeleteObjectResponse(
            deleteMarker = response.headers["x-amz-delete-marker"]?.toBooleanStrictOrNull(),
            versionId = response.headers["x-amz-version-id"],
            headers = response.headers,
        )
    }

    /**
     * S3 ListObjectsV2를 호출합니다.
     *
     * ```kotlin
     * val page = s3.listObjectsV2(
     *     S3KtorListObjectsRequest(bucket = "demo-bucket", prefix = "logs/", maxKeys = 100)
     * )
     * val keys = page.contents.map { it.key }
     * ```
     */
    suspend fun listObjectsV2(request: S3KtorListObjectsRequest): S3KtorListObjectsResponse {
        val response = httpClient.get(bucketUrl(request.bucket)) {
            parameter("list-type", "2")
            request.prefix?.let { parameter("prefix", it) }
            request.delimiter?.let { parameter("delimiter", it) }
            request.continuationToken?.let { parameter("continuation-token", it) }
            request.startAfter?.let { parameter("start-after", it) }
            request.maxKeys?.let { parameter("max-keys", it.toString()) }
            request.fetchOwner?.let { parameter("fetch-owner", it.toString()) }
        }.ensureSuccess()

        return S3KtorXml.parseListObjectsV2(response.bodyAsText())
    }

    /**
     * Multipart upload를 시작합니다.
     *
     * [metadata]의 key는 `x-amz-meta-` 접두사 없이 전달합니다.
     */
    suspend fun createMultipartUpload(
        bucket: String,
        key: String,
        contentType: String? = null,
        metadata: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): S3KtorMultipartUpload {
        val response = httpClient.post(objectUrl(bucket, key)) {
            parameter("uploads", "")
            contentType?.let { contentType(ContentType.parse(it)) }
            metadata.forEach { (name, value) -> header("x-amz-meta-$name", value) }
            headers.forEach { (name, value) -> header(name, value) }
        }.ensureSuccess()

        return S3KtorXml.parseCreateMultipartUpload(response.bodyAsText())
    }

    /**
     * Starts multipart upload with S3 server-side encryption request headers.
     */
    suspend fun createEncryptedMultipartUpload(
        bucket: String,
        key: String,
        encryption: S3KtorServerSideEncryption,
        contentType: String? = null,
        metadata: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): S3KtorMultipartUpload =
        createMultipartUpload(
            bucket = bucket,
            key = key,
            contentType = contentType,
            metadata = metadata,
            headers = headers + encryption.headers(),
        )

    /**
     * Multipart upload part를 byte array로 업로드합니다.
     */
    suspend fun uploadPart(
        bucket: String,
        key: String,
        uploadId: String,
        partNumber: Int,
        bytes: ByteArray,
    ): S3KtorCompletedPart =
        uploadPart(bucket, key, uploadId, partNumber, ByteArrayContent(bytes), bytes.size.toLong())

    /**
     * Multipart upload part를 streaming body로 업로드합니다.
     *
     * [partNumber]는 1 이상이어야 하며, [contentLength]는 음수일 수 없습니다.
     */
    suspend fun uploadPart(
        bucket: String,
        key: String,
        uploadId: String,
        partNumber: Int,
        body: OutgoingContent,
        contentLength: Long,
    ): S3KtorCompletedPart {
        require(partNumber > 0) { "partNumber must be positive. partNumber=$partNumber" }
        require(contentLength >= 0) { "contentLength must be non-negative. contentLength=$contentLength" }

        val response = httpClient.put(objectUrl(bucket, key)) {
            parameter("partNumber", partNumber.toString())
            parameter("uploadId", uploadId)
            header(HttpHeaders.ContentLength, contentLength.toString())
            setBody(body)
        }.ensureSuccess()

        return S3KtorCompletedPart(
            partNumber = partNumber,
            eTag = response.headers[HttpHeaders.ETag].orEmpty(),
        )
    }

    /**
     * Multipart upload를 완료합니다.
     *
     * [parts]는 비어 있을 수 없고, XML 생성 시 part number 순서로 정렬됩니다.
     */
    suspend fun completeMultipartUpload(
        bucket: String,
        key: String,
        uploadId: String,
        parts: List<S3KtorCompletedPart>,
    ): S3KtorCompleteMultipartUploadResponse {
        require(parts.isNotEmpty()) { "parts must not be empty." }
        val body = TextContent(
            text = S3KtorXml.completeMultipartUpload(parts),
            contentType = ContentType.Application.Xml,
        )

        val response = httpClient.post(objectUrl(bucket, key)) {
            parameter("uploadId", uploadId)
            setBody(body)
        }.ensureSuccess()

        return S3KtorXml.parseCompleteMultipartUpload(response.bodyAsText())
    }

    /**
     * Multipart upload를 중단합니다.
     */
    suspend fun abortMultipartUpload(bucket: String, key: String, uploadId: String) {
        httpClient.delete(objectUrl(bucket, key)) {
            parameter("uploadId", uploadId)
        }.ensureSuccess()
    }

    /**
     * GetObject presigned URL을 생성합니다.
     *
     * [expires]는 S3 SigV4 제약에 맞춰 1초 이상 7일 이하여야 합니다.
     */
    fun presignGetObject(bucket: String, key: String, expires: Duration): S3KtorPresignedRequest =
        presign(HttpMethod.Get, objectUrl(bucket, key), expires)

    /**
     * PutObject presigned URL을 생성합니다.
     *
     * [expires]는 S3 SigV4 제약에 맞춰 1초 이상 7일 이하여야 합니다.
     */
    fun presignPutObject(bucket: String, key: String, expires: Duration): S3KtorPresignedRequest =
        presign(HttpMethod.Put, objectUrl(bucket, key), expires)

    /**
     * Loads a text configuration file from S3 without coupling it to a Ktor
     * `ApplicationConfig` implementation.
     */
    suspend fun getConfigObject(
        bucket: String,
        key: String,
        charset: Charset = StandardCharsets.UTF_8,
    ): S3KtorConfigObject {
        val response = getObject(bucket, key)
        return S3KtorConfigObject(
            bucket = bucket,
            key = key,
            text = response.bytes.toString(charset),
            charset = charset,
            contentType = response.contentType,
            metadata = response.metadata,
        )
    }

    /**
     * Stores a text configuration file in S3.
     */
    suspend fun putConfigObject(
        bucket: String,
        key: String,
        text: String,
        charset: Charset = StandardCharsets.UTF_8,
        contentType: String? = null,
        metadata: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): S3KtorPutObjectResponse {
        val bytes = text.toByteArray(charset)
        val resolvedContentType = contentType
            ?: S3KtorContentTypes.orFallback(S3KtorContentTypes.Default.detect(key, bytes), "text/plain; charset=${charset.name()}")

        return putObject(
            bucket = bucket,
            key = key,
            bytes = bytes,
            contentType = resolvedContentType,
            metadata = metadata,
            headers = headers,
        )
    }

    override fun close() {
        try {
            if (closeClient) httpClient.close()
        } finally {
            if (closeCredentialsProvider && credentialsProvider is AutoCloseable) {
                (credentialsProvider as AutoCloseable).close()
            }
        }
    }

    private fun URLBuilder.applyS3Endpoint(bucket: String? = null, key: String? = null): URLBuilder {
        if (endpointOverride != null) {
            takeFrom(endpointOverride)
        } else {
            protocol = io.ktor.http.URLProtocol.HTTPS
            host = "s3.$region.amazonaws.com"
        }

        val useVirtualHosted = bucket != null &&
                endpointOverride == null &&
                addressingStyle == S3KtorAddressingStyle.VirtualHosted &&
                bucket.isVirtualHostedSafeBucket()

        if (useVirtualHosted) {
            host = "$bucket.$host"
            encodedPathSegments = buildEncodedPathSegments(null, key)
        } else {
            encodedPathSegments = buildEncodedPathSegments(bucket, key)
        }
        return this
    }

    private fun bucketUrl(bucket: String): Url {
        bucket.requireNotBlank("bucket")
        return URLBuilder().applyS3Endpoint(bucket = bucket).build()
    }

    private fun objectUrl(bucket: String, key: String): Url {
        bucket.requireNotBlank("bucket")
        key.requireNotBlank("key")
        return URLBuilder().applyS3Endpoint(bucket = bucket, key = key).build()
    }

    private fun presign(method: HttpMethod, url: Url, expires: Duration): S3KtorPresignedRequest {
        require(!expires.isNegative && !expires.isZero) { "expires must be positive. expires=$expires" }
        require(expires in MIN_PRESIGN_EXPIRY..MAX_PRESIGN_EXPIRY) {
            "expires must be between $MIN_PRESIGN_EXPIRY and $MAX_PRESIGN_EXPIRY. expires=$expires"
        }

        val sdkRequest = SdkHttpFullRequest.builder()
            .uri(URI(url.toString()))
            .method(SdkHttpMethod.fromValue(method.value))
            .putHeader("x-amz-content-sha256", UNSIGNED_PAYLOAD)
            .build()

        val signed = signer.sign { builder ->
            builder.identity(credentialsProvider.resolveCredentials())
                .request(sdkRequest)
                .putProperty(AwsV4HttpSigner.REGION_NAME, region)
                .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, S3_SERVICE)
                .putProperty(AwsV4HttpSigner.DOUBLE_URL_ENCODE, false)
                .putProperty(AwsV4HttpSigner.NORMALIZE_PATH, false)
                .putProperty(AwsV4HttpSigner.AUTH_LOCATION, AwsV4FamilyHttpSigner.AuthLocation.QUERY_STRING)
                .putProperty(AwsV4HttpSigner.PAYLOAD_SIGNING_ENABLED, false)
                .putProperty(AwsV4HttpSigner.EXPIRATION_DURATION, expires)

            if (signingClock != null) {
                builder.putProperty(HttpSigner.SIGNING_CLOCK, signingClock)
            }
        }

        return S3KtorPresignedRequest(method.value, signed.toUrl())
    }
}

/**
 * Creates an S3 REST client backed by an internal Ktor CIO HTTP client.
 *
 * ## Behavior / Contract
 *
 * The created `HttpClient` has S3-specific SigV4 configuration installed. Payload signing,
 * double URL encoding, and path normalization are disabled for S3 streaming body and presigned
 * URL compatibility. Closing the returned [S3KtorClient] also closes the internal `HttpClient`.
 *
 * **Credentials provider ownership**: when `credentialsProvider` is `null` (the default),
 * this factory creates a [DefaultCredentialsProvider] and the returned client takes ownership —
 * it will be closed when [S3KtorClient.close] is called. When a provider is supplied by the
 * caller, the client does not close it; the caller retains ownership.
 *
 * ```kotlin
 * import io.bluetape4k.aws.ktor.s3.S3KtorAddressingStyle
 * import io.bluetape4k.aws.ktor.s3.s3KtorClientOf
 * import io.ktor.http.Url
 *
 * suspend fun upload() {
 *     s3KtorClientOf(
 *         region = "ap-northeast-2",
 *         endpointOverride = Url("http://localhost:4566"),
 *         addressingStyle = S3KtorAddressingStyle.Path,
 *     ).use { s3 ->
 *         s3.putObject("demo-bucket", "hello.txt", "hello".encodeToByteArray())
 *     }
 * }
 * ```
 */
fun s3KtorClientOf(
    region: String,
    credentialsProvider: AwsCredentialsProvider? = null,
    endpointOverride: Url? = null,
    addressingStyle: S3KtorAddressingStyle = S3KtorAddressingStyle.VirtualHosted,
    signingClock: Clock? = null,
    httpClientCustomizers: Iterable<AwsKtorHttpClientCustomizer> = emptyList(),
): S3KtorClient {
    val ownsProvider = credentialsProvider == null
    val effectiveProvider = credentialsProvider ?: DefaultCredentialsProvider.builder().build()

    try {
        val client = HttpClient(CIO) {
            install(AwsSigV4Plugin) {
                this.region = region
                service = S3_SERVICE
                this.credentialsProvider = effectiveProvider
                authLocation = AwsSigV4AuthLocation.Header
                doubleUrlEncode = false
                normalizePath = false
                payloadSigningEnabled = false
                this.signingClock = signingClock
            }
            httpClientCustomizers.forEach { it.customize(this) }
        }

        return S3KtorClient(
            httpClient = client,
            region = region,
            credentialsProvider = effectiveProvider,
            endpointOverride = endpointOverride,
            addressingStyle = addressingStyle,
            signingClock = signingClock,
            closeClient = true,
            closeCredentialsProvider = ownsProvider,
        )
    } catch (e: Throwable) {
        if (ownsProvider && effectiveProvider is AutoCloseable) effectiveProvider.close()
        throw e
    }
}

/**
 * Creates an S3 REST client by inheriting shared [AwsKtorDefaults].
 *
 * Service-specific arguments override shared defaults. The caller must provide
 * either [region] or [AwsKtorDefaults.region].
 */
fun s3KtorClientOf(
    defaults: AwsKtorDefaults,
    region: String? = null,
    credentialsProvider: AwsCredentialsProvider? = null,
    endpointOverride: Url? = null,
    addressingStyle: S3KtorAddressingStyle = S3KtorAddressingStyle.VirtualHosted,
    signingClock: Clock? = null,
    httpClientCustomizers: Iterable<AwsKtorHttpClientCustomizer> = emptyList(),
): S3KtorClient =
    s3KtorClientOf(
        region = requireNotNull(region?.takeIf { it.isNotBlank() } ?: defaults.region?.takeIf { it.isNotBlank() }) {
            "region must be configured."
        },
        credentialsProvider = credentialsProvider ?: defaults.javaCredentialsProvider,
        endpointOverride = endpointOverride ?: defaults.endpointOverride,
        addressingStyle = addressingStyle,
        signingClock = signingClock ?: defaults.signingClock,
        httpClientCustomizers = defaults.httpClientCustomizers + httpClientCustomizers,
    )

private fun io.ktor.client.request.HttpRequestBuilder.applyPutHeaders(request: S3KtorPutObjectRequest) {
    request.contentType?.let { contentType(ContentType.parse(it)) }
    request.metadata.forEach { (name, value) -> header("x-amz-meta-$name", value) }
    request.headers.forEach { (name, value) -> header(name, value) }
}

private suspend fun HttpResponse.ensureSuccess(): HttpResponse {
    if (status.value in 200..299) {
        return this
    }

    val parsed = S3KtorXml.parseError(bodyAsText())
    throw S3KtorException(
        status = status,
        code = parsed.code,
        message = parsed.message,
        requestId = parsed.requestId,
        hostId = parsed.hostId,
        headers = headers,
    )
}

private fun HttpResponse.s3Metadata(): Map<String, String> =
    headers.entries()
        .filter { (name, _) -> name.startsWith(S3_METADATA_PREFIX, ignoreCase = true) }
        .associate { (name, values) -> name.substring(S3_METADATA_PREFIX.length) to values.joinToString(",") }

private fun buildEncodedPathSegments(bucket: String?, key: String?): List<String> =
    buildList {
        add("")
        if (bucket != null) {
            add(bucket.encodeURLPathPart())
        }
        if (key != null) {
            addAll(key.split('/').map { it.encodeURLPathPart() })
        }
    }

private fun String.isVirtualHostedSafeBucket(): Boolean {
    if ('.' in this) return false
    if (!matches(Regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$"))) return false
    if (".." in this || ".-" in this || "-." in this) return false
    return true
}

private fun SignedRequest.toUrl(): Url {
    val request = request()
    return URLBuilder(request.getUri().toString()).build()
}
