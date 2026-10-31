package io.bluetape4k.aws.ktor.s3

import aws.sdk.kotlin.runtime.auth.credentials.DefaultChainCredentialsProvider
import aws.smithy.kotlin.runtime.auth.awscredentials.CloseableCredentialsProvider
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.collections.Attributes
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorHttpClientCustomizer
import io.bluetape4k.aws.ktor.client.AwsSigV4AuthLocation
import io.bluetape4k.aws.ktor.client.AwsSigV4Plugin
import io.bluetape4k.codec.decodeBase64String
import io.bluetape4k.javatimes.days
import io.bluetape4k.javatimes.minutes
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.support.toUtf8String
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class S3KtorClientTest {

    private companion object: KLoggingChannel() {
        private val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-05-10T01:02:03Z"), ZoneOffset.UTC)
    }

    @Test
    fun `get object response does not render body bytes`() {
        val body = byteArrayOf(83, 69, 67, 82, 69, 84)
        val rendered = S3KtorGetObjectResponse(
            bytes = body,
            eTag = "etag",
            contentType = "application/octet-stream",
            contentLength = body.size.toLong(),
            metadata = emptyMap(),
            headers = headersOf(),
        ).toString()

        rendered shouldNotContain body.contentToString()
    }

    @Test
    fun `S3 diagnostics omit request identifiers`() = runSuspendIO {
        val logger = LoggerFactory.getLogger(S3KtorClient::class.java) as Logger
        val appender = ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        val previousLevel = logger.level
        logger.addAppender(appender)
        logger.level = Level.DEBUG
        val s3 = s3Client(response = { request -> s3DiagnosticResponse(request) })

        try {
            runS3OperationsWithCanaryIdentifiers(s3)
        } finally {
            s3.close()
            logger.detachAppender(appender)
            logger.level = previousLevel
        }

        assertS3DiagnosticsAreRedacted(appender)
    }

    @Test
    fun `PutObject는 S3 path-style URL과 unsigned payload 헤더로 요청한다`() = runSuspendIO {
        lateinit var captured: HttpRequestData

        val s3 = s3Client(
            capture = { captured = it },
            response = {
                respond(
                    content = "",
                    headers = headersOf(HttpHeaders.ETag, "\"etag-1\""),
                )
            },
        )

        val response = s3.putObject(
            bucket = "demo-bucket",
            key = "logs/2026/app log.txt",
            bytes = "hello".encodeToByteArray(),
            contentType = "text/plain",
            metadata = mapOf("source" to "ktor"),
        )

        log.debug { "captured=$captured" }

        captured.method shouldBeEqualTo HttpMethod.Put
        captured.url.host shouldBeEqualTo "localhost"
        captured.url.encodedPath shouldBeEqualTo "/demo-bucket/logs/2026/app%20log.txt"
        captured.headers["x-amz-content-sha256"] shouldBeEqualTo "UNSIGNED-PAYLOAD"
        captured.headers["x-amz-meta-source"] shouldBeEqualTo "ktor"
        captured.headers[HttpHeaders.Authorization] shouldContain "AWS4-HMAC-SHA256"
        response.eTag shouldBeEqualTo "\"etag-1\""

        s3.close()
    }

    @Test
    fun `PutObjectDetectingContentType resolves content type from key`() = runSuspendIO {
        lateinit var captured: HttpRequestData
        val s3 = s3Client(
            capture = { captured = it },
            response = { respond("") },
        )

        s3.putObjectDetectingContentType(
            bucket = "demo-bucket",
            key = "docs/app.json",
            bytes = """{"status":"ok"}""".encodeToByteArray(),
        )

        log.debug { "captured=$captured" }
        captured.body.contentType.toString() shouldBeEqualTo "application/json"

        s3.close()
    }

    @Test
    fun `server-side KMS encryption helper renders S3 headers`() = runSuspendIO {
        lateinit var captured: HttpRequestData
        val s3 = s3Client(
            capture = { captured = it },
            response = { respond("") },
        )

        s3.putEncryptedObject(
            bucket = "demo-bucket",
            key = "secure/report.txt",
            bytes = "secret".encodeToByteArray(),
            encryption = S3KtorServerSideEncryption.Kms(
                keyId = "alias/demo",
                encryptionContext = mapOf("tenant" to "demo"),
                bucketKeyEnabled = true,
            ),
        )

        log.debug { "captured=$captured" }
        captured.headers["x-amz-server-side-encryption"] shouldBeEqualTo "aws:kms"
        captured.headers["x-amz-server-side-encryption-aws-kms-key-id"] shouldBeEqualTo "alias/demo"
        captured.headers["x-amz-server-side-encryption-bucket-key-enabled"] shouldBeEqualTo "true"
        captured.headers["x-amz-server-side-encryption-context"].decodeBase64String() shouldBeEqualTo
            """{"tenant":"demo"}"""

        s3.close()
    }

    @Test
    fun `config object helper stores and loads text config`() = runSuspendIO {
        val captured = mutableListOf<HttpRequestData>()
        val s3 = s3Client(
            capture = { captured += it },
            response = { request ->
                when (request.method) {
                    HttpMethod.Put -> respond("")
                    HttpMethod.Get -> respond(
                        content = "ktor { deployment { port = 8080 } }",
                        headers = headersOf(
                            HttpHeaders.ContentType to listOf("application/hocon"),
                            "x-amz-meta-source" to listOf("s3"),
                        ),
                    )
                    else -> respond("unexpected request", HttpStatusCode.BadRequest)
                }
            },
        )

        s3.putConfigObject(
            bucket = "demo-bucket",
            key = "config/application.conf",
            text = "ktor { deployment { port = 8080 } }",
            metadata = mapOf("source" to "s3"),
        )
        val config = s3.getConfigObject("demo-bucket", "config/application.conf")

        log.debug { "captured=$captured" }
        captured[0].body.contentType.toString() shouldBeEqualTo "text/plain; charset=UTF-8"

        log.debug { "config=$config" }
        config.text shouldBeEqualTo "ktor { deployment { port = 8080 } }"
        config.contentType shouldBeEqualTo "application/hocon"
        config.metadata["source"] shouldBeEqualTo "s3"

        s3.close()
    }

    @Test
    fun `client-side envelope encryption uploads ciphertext and restores plaintext`() = runSuspendIO {
        val storedObjects = mutableMapOf<String, Pair<ByteArray, Headers>>()
        val s3 = s3Client(
            response = { request ->
                val objectKey = request.url.encodedPath
                when (request.method) {
                    HttpMethod.Put -> {
                        storedObjects[objectKey] = request.body.toByteArray() to request.headers
                        respond("")
                    }
                    HttpMethod.Get -> {
                        val (bytes, headers) = requireNotNull(storedObjects[objectKey])
                        respond(content = bytes, headers = headers)
                    }
                    else -> respond("unexpected request", HttpStatusCode.BadRequest)
                }
            },
        )
        val encryption = S3KtorClientSideEncryption(InMemoryDataKeyProvider())
        val plaintext = "client side secret".toUtf8Bytes()

        encryption.putEncryptedObject(
            s3 = s3,
            bucket = "demo-bucket",
            key = "secure/client.txt",
            plaintext = plaintext,
            encryptionContext = mapOf("purpose" to "test"),
        )
        val stored = storedObjects.getValue("/demo-bucket/secure/client.txt")
        val decrypted = encryption.getEncryptedObjectBytes(
            s3 = s3,
            bucket = "demo-bucket",
            key = "secure/client.txt",
            encryptionContext = mapOf("purpose" to "test"),
        )

        stored.first.toUtf8String() shouldNotBeEqualTo plaintext.toUtf8String()
        decrypted.toUtf8String() shouldBeEqualTo plaintext.toUtf8String()

        s3.close()
    }

    @Test
    fun `ListObjectsV2는 continuation token을 쿼리에 유지한다`() = runSuspendIO {
        lateinit var captured: HttpRequestData
        val s3 = s3Client(
            capture = { captured = it },
            response = {
                respond(
                    content = """
                        <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                          <Name>demo-bucket</Name>
                          <Prefix>logs/</Prefix>
                          <IsTruncated>false</IsTruncated>
                          <Contents>
                            <Key>logs/app.log</Key>
                            <ETag>&quot;etag-1&quot;</ETag>
                            <Size>5</Size>
                          </Contents>
                        </ListBucketResult>
                    """.trimIndent(),
                    headers = headersOf(HttpHeaders.ContentType, "application/xml"),
                )
            },
        )

        val response = s3.listObjectsV2(
            S3KtorListObjectsRequest(
                bucket = "demo-bucket",
                prefix = "logs/",
                continuationToken = "next/token+1",
                maxKeys = 25,
            )
        )

        log.debug { "captured=$captured" }
        captured.method shouldBeEqualTo HttpMethod.Get
        captured.url.encodedPath shouldBeEqualTo "/demo-bucket"
        captured.url.parameters["list-type"] shouldBeEqualTo "2"
        captured.url.parameters["prefix"] shouldBeEqualTo "logs/"
        captured.url.parameters["continuation-token"] shouldBeEqualTo "next/token+1"
        captured.url.parameters["max-keys"] shouldBeEqualTo "25"

        log.debug { "response=$response" }
        response.contents.single().key shouldBeEqualTo "logs/app.log"

        s3.close()
    }

    @Test
    fun `S3 error XML은 S3KtorException으로 변환한다`() = runSuspendIO {
        val s3 = s3Client(
            response = {
                respond(
                    content = """
                        <Error>
                          <Code>NoSuchKey</Code>
                          <Message>missing key</Message>
                          <RequestId>request-1</RequestId>
                          <HostId>host-1</HostId>
                        </Error>
                    """.trimIndent(),
                    status = HttpStatusCode.NotFound,
                    headers = headersOf(HttpHeaders.ContentType, "application/xml"),
                )
            },
        )

        val error = assertFailsWith<S3KtorException> {
            s3.getObjectBytes("demo-bucket", "missing.txt")
        }

        error.status shouldBeEqualTo HttpStatusCode.NotFound
        error.code shouldBeEqualTo "NoSuchKey"
        error.message shouldBeEqualTo "missing key"
        error.requestId shouldBeEqualTo "request-1"

        s3.close()
    }

    @Test
    fun `Multipart upload는 S3 query contract를 사용한다`() = runSuspendIO {
        val captured = mutableListOf<HttpRequestData>()
        val s3 = s3Client(
            capture = { captured += it },
            response = { request ->
                when (request.method) {
                    HttpMethod.Post if "uploads" in request.url.parameters.names() ->
                        respond(
                            content = """
                                        <InitiateMultipartUploadResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                                          <Bucket>demo-bucket</Bucket>
                                          <Key>logs/app.log</Key>
                                          <UploadId>upload-1</UploadId>
                                        </InitiateMultipartUploadResult>
                                    """.trimIndent(),
                            headers = headersOf(HttpHeaders.ContentType, "application/xml"),
                        )
                    HttpMethod.Put ->
                        respond(
                            content = "",
                            headers = headersOf(HttpHeaders.ETag, "\"part-etag-1\""),
                        )
                    HttpMethod.Post if request.url.parameters["uploadId"] == "upload-1" ->
                        respond(
                            content = """
                                        <CompleteMultipartUploadResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                                          <Location>http://localhost:4566/demo-bucket/logs/app.log</Location>
                                          <Bucket>demo-bucket</Bucket>
                                          <Key>logs/app.log</Key>
                                          <ETag>&quot;object-etag&quot;</ETag>
                                        </CompleteMultipartUploadResult>
                                    """.trimIndent(),
                            headers = headersOf(HttpHeaders.ContentType, "application/xml"),
                        )
                    HttpMethod.Delete if request.url.parameters["uploadId"] == "upload-1" -> respond("")
                    else -> respond("unexpected request", HttpStatusCode.BadRequest)
                }
            },
        )

        val upload = s3.createMultipartUpload("demo-bucket", "logs/app.log")
        val part = s3.uploadPart("demo-bucket", "logs/app.log", upload.uploadId, 1, "hello".encodeToByteArray())
        val complete = s3.completeMultipartUpload("demo-bucket", "logs/app.log", upload.uploadId, listOf(part))
        s3.abortMultipartUpload("demo-bucket", "logs/app.log", upload.uploadId)

        upload.uploadId shouldBeEqualTo "upload-1"
        part.eTag shouldBeEqualTo "\"part-etag-1\""
        complete.eTag shouldBeEqualTo "\"object-etag\""

        captured.forEach { log.debug { "captured=$it" } }
        captured[0].url.parameters.names() shouldContain "uploads"
        captured[1].url.parameters["partNumber"] shouldBeEqualTo "1"
        captured[1].url.parameters["uploadId"] shouldBeEqualTo "upload-1"
        captured[2].url.parameters["uploadId"] shouldBeEqualTo "upload-1"
        captured[3].method shouldBeEqualTo HttpMethod.Delete

        s3.close()
    }

    @Test
    fun `multipart upload accepts an empty final part body`() = runSuspendIO {
        val s3 = s3Client(
            response = { respond(content = "", headers = headersOf(HttpHeaders.ETag, "\"empty-etag\"")) },
        )

        val part = s3.uploadPart(
            bucket = "demo-bucket",
            key = "logs/app.log",
            uploadId = "upload-1",
            partNumber = 1,
            bytes = byteArrayOf(),
        )

        part.eTag shouldBeEqualTo "\"empty-etag\""
        s3.close()
    }

    @Test
    fun `Presign은 고정 SigV4 vector와 원래 path encoding을 보존한다`() = runSuspendIO {
        val s3 = s3Client(endpointOverride = null)

        val presigned = s3.presignGetObject(
            bucket = "demo.bucket",
            key = "logs/2026/app log.txt",
            expires = 15.minutes(),
        )

        presigned.method shouldBeEqualTo "GET"
        presigned.url.host shouldBeEqualTo "s3.ap-northeast-2.amazonaws.com"
        presigned.url.encodedPath shouldBeEqualTo "/demo.bucket/logs/2026/app%20log.txt"
        presigned.url.parameters["X-Amz-Algorithm"] shouldBeEqualTo "AWS4-HMAC-SHA256"
        presigned.url.parameters["X-Amz-Expires"] shouldBeEqualTo "900"
        presigned.url.parameters["X-Amz-SignedHeaders"] shouldBeEqualTo "host"
        presigned.url.parameters["X-Amz-Signature"] shouldBeEqualTo
            "3db75a0ad67e1d88633145f9c3ffca7556f32c68680f5071e6e2933738adfcbd"

        s3.close()
    }

    @Test
    fun `AWS S3 endpoint는 DNS-safe bucket에 virtual-hosted URL을 사용한다`() = runSuspendIO {
        lateinit var captured: HttpRequestData
        val s3 = s3Client(
            endpointOverride = null,
            capture = { captured = it },
            response = { respond("") },
        )

        s3.deleteObject("demo-bucket", "logs/app.txt")

        log.debug { "captured=$captured" }
        captured.method shouldBeEqualTo HttpMethod.Delete
        captured.url.host shouldBeEqualTo "demo-bucket.s3.ap-northeast-2.amazonaws.com"
        captured.url.encodedPath shouldBeEqualTo "/logs/app.txt"

        s3.close()
    }

    @Test
    fun `AWS S3 endpoint는 dotted bucket을 path-style URL로 fallback한다`() = runSuspendIO {
        lateinit var captured: HttpRequestData
        val s3 = s3Client(
            endpointOverride = null,
            capture = { captured = it },
            response = { respond("") },
        )

        s3.deleteObject("demo.bucket", "logs/app.txt")

        log.debug { "captured=$captured" }
        captured.method shouldBeEqualTo HttpMethod.Delete
        captured.url.host shouldBeEqualTo "s3.ap-northeast-2.amazonaws.com"
        captured.url.encodedPath shouldBeEqualTo "/demo.bucket/logs/app.txt"

        s3.close()
    }

    @Test
    fun `AWS S3 endpoint는 명시적 Path addressing style을 따른다`() = runSuspendIO {
        lateinit var captured: HttpRequestData
        val s3 = s3Client(
            endpointOverride = null,
            addressingStyle = S3KtorAddressingStyle.Path,
            capture = { captured = it },
            response = { respond("") },
        )

        s3.deleteObject("demo-bucket", "logs/app.txt")

        log.debug { "captured=$captured" }
        captured.method shouldBeEqualTo HttpMethod.Delete
        captured.url.host shouldBeEqualTo "s3.ap-northeast-2.amazonaws.com"
        captured.url.encodedPath shouldBeEqualTo "/demo-bucket/logs/app.txt"

        s3.close()
    }

    @Test
    fun `Presign 만료 시간은 S3 SigV4 허용 범위를 검증한다`() = runSuspendIO {
        val s3 = s3Client(endpointOverride = null)

        assertFailsWith<IllegalArgumentException> {
            s3.presignGetObject("demo-bucket", "logs/app.txt", Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            s3.presignPutObject("demo-bucket", "logs/app.txt", 7.days() + 1.seconds())
        }

        s3.close()
    }

    @Test
    fun `presign은 원격 HTTP endpoint를 credentials resolve 전에 거부한다`() = runSuspendIO {
        var resolves = 0
        val credentialsProvider = object: CredentialsProvider {
            override suspend fun resolve(attributes: Attributes): Credentials {
                resolves += 1
                return Credentials("akid", "secret")
            }
        }
        assertFailsWith<IllegalArgumentException> {
            s3KtorClientOf(
                region = "ap-northeast-2",
                credentialsProvider = credentialsProvider,
                endpointOverride = Url("http://s3.example.com:4566"),
                addressingStyle = S3KtorAddressingStyle.Path,
                allowInsecureLocalHttpEndpoint = false,
            )
        }

        resolves shouldBeEqualTo 0
    }

    @Test
    fun `presign은 session token을 포함하고 signature를 로그에 쓰지 않는다`() = runSuspendIO {
        val logger = LoggerFactory.getLogger(S3KtorClient::class.java) as Logger
        val appender = ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        val previousLevel = logger.level
        logger.addAppender(appender)
        logger.level = Level.DEBUG
        val s3 = s3Client(credentialsProvider = testCredentialsProvider(sessionToken = "session-token"))

        try {
            val presigned = s3.presignGetObject("demo-bucket", "logs/app.txt", 15.minutes())
            presigned.url.parameters["X-Amz-Security-Token"] shouldBeEqualTo "session-token"
        } finally {
            s3.close()
            logger.detachAppender(appender)
            logger.level = previousLevel
        }

        val diagnostics = appender.list.joinToString("\n") { it.formattedMessage }
        diagnostics shouldNotContain "X-Amz-Signature"
        diagnostics shouldNotContain "session-token"
        diagnostics shouldNotContain "X-Amz-Credential"
    }

    @Test
    fun `shared AWS defaults provide S3 region endpoint credentials and signing clock`() = runSuspendIO {
        val credentialsProvider = testCredentialsProvider()
        val s3 = s3KtorClientOf(
            defaults = AwsKtorDefaults(
                region = "ap-northeast-2",
                endpointOverride = Url("http://localhost:4566"),
                kotlinCredentialsProvider = credentialsProvider,
                signingClock = FIXED_CLOCK,
                allowInsecureLocalHttpEndpoint = true,
            ),
            addressingStyle = S3KtorAddressingStyle.Path,
        )

        val presigned = s3.presignGetObject("demo-bucket", "logs/app.txt", 15.minutes())

        presigned.url.host shouldBeEqualTo "localhost"
        presigned.url.encodedPath shouldBeEqualTo "/demo-bucket/logs/app.txt"
        presigned.url.parameters["X-Amz-Date"] shouldBeEqualTo "20260510T010203Z"

        s3.close()
    }

    @Test
    fun `close preserves primary failure and suppresses provider close failure`() {
        val httpClient = mockk<HttpClient>()
        val clientFailure = IllegalStateException("http client close failed")
        val providerFailure = IllegalStateException("credentials provider close failed")
        val credentialsProvider = CloseThrowingCredentialsProvider(providerFailure)
        every { httpClient.close() } throws clientFailure

        val s3 = S3KtorClient(
            httpClient = httpClient,
            region = "ap-northeast-2",
            credentialsProvider = credentialsProvider,
            endpointOverride = Url("http://localhost:4566"),
            addressingStyle = S3KtorAddressingStyle.Path,
            signingClock = FIXED_CLOCK,
            closeClient = true,
            closeCredentialsProvider = true,
            allowInsecureLocalHttpEndpoint = true,
        )

        val error = assertFailsWith<IllegalStateException> {
            s3.close()
        }

        error shouldBeSameInstanceAs clientFailure
        error.suppressed.single() shouldBeSameInstanceAs providerFailure
        s3.close()
        verify(exactly = 1) { httpClient.close() }
        credentialsProvider.closeCount shouldBeEqualTo 1
    }

    @Test
    fun `factory closes owned default credentials provider when client initialization fails`() {
        mockkConstructor(DefaultChainCredentialsProvider::class)
        every { anyConstructed<DefaultChainCredentialsProvider>().close() } returns Unit

        try {
            assertFailsWith<IllegalStateException> {
                s3KtorClientOf(
                    region = "ap-northeast-2",
                    httpClientCustomizers = listOf(
                        AwsKtorHttpClientCustomizer { error("customizer initialization failed") }
                    ),
                )
            }

            verify(exactly = 1) { anyConstructed<DefaultChainCredentialsProvider>().close() }
        } finally {
            unmockkConstructor(DefaultChainCredentialsProvider::class)
        }
    }

    private fun s3Client(
        capture: (HttpRequestData) -> Unit = {},
        response: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { respond("") },
        endpointOverride: Url? = Url("http://localhost:4566"),
        addressingStyle: S3KtorAddressingStyle = S3KtorAddressingStyle.VirtualHosted,
        credentialsProvider: CredentialsProvider = testCredentialsProvider(),
        allowInsecureLocalHttpEndpoint: Boolean = true,
    ): S3KtorClient {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    capture(request)
                    response(request)
                }
            }
            install(AwsSigV4Plugin) {
                region = "ap-northeast-2"
                service = "s3"
                this.credentialsProvider = credentialsProvider
                authLocation = AwsSigV4AuthLocation.Header
                doubleUrlEncode = false
                normalizePath = false
                payloadSigningEnabled = false
                signingClock = FIXED_CLOCK
                this.allowInsecureLocalHttpEndpoint = allowInsecureLocalHttpEndpoint
            }
        }

        return S3KtorClient(
            httpClient = client,
            region = "ap-northeast-2",
            credentialsProvider = credentialsProvider,
            endpointOverride = endpointOverride,
            addressingStyle = addressingStyle,
            signingClock = FIXED_CLOCK,
            closeClient = true,
            allowInsecureLocalHttpEndpoint = allowInsecureLocalHttpEndpoint,
        )
    }

    private suspend fun MockRequestHandleScope.s3DiagnosticResponse(request: HttpRequestData): HttpResponseData {
        val body = when {
            request.url.parameters.contains("list-type") ->
                """<ListBucketResult>
                    <Name>bucket-canary</Name><Prefix>prefix-canary</Prefix><KeyCount>0</KeyCount>
                    <MaxKeys>1000</MaxKeys><IsTruncated>false</IsTruncated>
                </ListBucketResult>""".trimIndent()
            request.url.parameters.contains("uploads") ->
                """<InitiateMultipartUploadResult>
                    <Bucket>bucket-canary</Bucket><Key>object-key-canary</Key>
                    <UploadId>upload-id-canary</UploadId>
                </InitiateMultipartUploadResult>""".trimIndent()
            request.method == HttpMethod.Post -> "<CompleteMultipartUploadResult/>"
            request.method == HttpMethod.Get -> "object-body-canary"
            else -> ""
        }

        return respond(content = body, headers = headersOf(HttpHeaders.ETag, "etag-canary"))
    }

    private suspend fun runS3OperationsWithCanaryIdentifiers(s3: S3KtorClient) {
        s3.putObject("bucket-canary", "object-key-canary", byteArrayOf(1), "application/canary")
        s3.getObject("bucket-canary", "object-key-canary")
        s3.getObjectStream("bucket-canary", "object-key-canary")
        s3.deleteObject("bucket-canary", "object-key-canary")
        s3.listObjectsV2(
            S3KtorListObjectsRequest(
                bucket = "bucket-canary",
                prefix = "prefix-canary",
                continuationToken = "continuation-token-canary",
                startAfter = "start-after-canary",
            ),
        )
        s3.createMultipartUpload("bucket-canary", "object-key-canary")
        s3.uploadPart("bucket-canary", "object-key-canary", "upload-id-canary", 1, byteArrayOf(2))
        s3.completeMultipartUpload(
            "bucket-canary",
            "object-key-canary",
            "upload-id-canary",
            listOf(S3KtorCompletedPart(partNumber = 1, eTag = "etag-canary")),
        )
        s3.abortMultipartUpload("bucket-canary", "object-key-canary", "upload-id-canary")
    }

    private fun assertS3DiagnosticsAreRedacted(appender: ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>) {
        val diagnostics = appender.list.joinToString("\n") { it.formattedMessage }
        diagnostics shouldContain "listObjectsV2"
        diagnostics shouldContain "uploadPart"
        diagnostics shouldContain "completeMultipartUpload"
        listOf(
            "bucket-canary",
            "object-key-canary",
            "prefix-canary",
            "continuation-token-canary",
            "start-after-canary",
            "upload-id-canary",
            "etag-canary",
            "object-body-canary",
            "application/canary",
        ).forEach { diagnostics shouldNotContain it }
    }
}

private fun OutgoingContent.toByteArray(): ByteArray =
    when (this) {
        is OutgoingContent.ByteArrayContent -> bytes()
        else -> error("Unsupported test content: ${this::class.qualifiedName}")
    }

private class InMemoryDataKeyProvider: S3KtorDataKeyProvider {
    private val key = ByteArray(32) { index -> index.toByte() }

    override suspend fun generateDataKey(encryptionContext: Map<String, String>): S3KtorDataKey =
        S3KtorDataKey(
            plaintextKey = key,
            encryptedKey = key.reversedArray(),
            keyId = "in-memory",
        )

    override suspend fun decryptDataKey(
        encryptedDataKey: ByteArray,
        encryptionContext: Map<String, String>,
    ): ByteArray =
        encryptedDataKey.reversedArray()
}

private class CloseThrowingCredentialsProvider(
    private val closeFailure: RuntimeException,
): CloseableCredentialsProvider {

    var closeCount = 0
        private set

    override suspend fun resolve(attributes: Attributes): Credentials =
        Credentials("akid", "secret")

    override fun close() {
        closeCount += 1
        throw closeFailure
    }
}

private fun testCredentialsProvider(
    accessKeyId: String = "akid",
    secretAccessKey: String = "secret",
    sessionToken: String? = null,
): CredentialsProvider = object: CredentialsProvider {
    override suspend fun resolve(attributes: Attributes): Credentials =
        Credentials(accessKeyId, secretAccessKey, sessionToken)
}
