package io.bluetape4k.aws.ktor.s3

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.aws.ktor.client.AwsSigV4AuthLocation
import io.bluetape4k.aws.ktor.client.AwsSigV4Plugin
import io.bluetape4k.junit5.coroutines.runSuspendIO
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
import io.ktor.http.headersOf
import org.junit.jupiter.api.Test
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class S3KtorClientTest {

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

        captured.method shouldBeEqualTo HttpMethod.Put
        captured.url.host shouldBeEqualTo "localhost"
        captured.url.encodedPath shouldBeEqualTo "/demo-bucket/logs/2026/app%20log.txt"
        captured.headers["x-amz-content-sha256"] shouldBeEqualTo "UNSIGNED-PAYLOAD"
        captured.headers["x-amz-meta-source"] shouldBeEqualTo "ktor"
        captured.headers[HttpHeaders.Authorization].orEmpty() shouldContain "AWS4-HMAC-SHA256"
        response.eTag shouldBeEqualTo "\"etag-1\""

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

        captured.method shouldBeEqualTo HttpMethod.Get
        captured.url.encodedPath shouldBeEqualTo "/demo-bucket"
        captured.url.parameters["list-type"] shouldBeEqualTo "2"
        captured.url.parameters["prefix"] shouldBeEqualTo "logs/"
        captured.url.parameters["continuation-token"] shouldBeEqualTo "next/token+1"
        captured.url.parameters["max-keys"] shouldBeEqualTo "25"
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
                when {
                    request.method == HttpMethod.Post && "uploads" in request.url.parameters.names() -> respond(
                        content = """
                            <InitiateMultipartUploadResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                              <Bucket>demo-bucket</Bucket>
                              <Key>logs/app.log</Key>
                              <UploadId>upload-1</UploadId>
                            </InitiateMultipartUploadResult>
                        """.trimIndent(),
                        headers = headersOf(HttpHeaders.ContentType, "application/xml"),
                    )
                    request.method == HttpMethod.Put -> respond(
                        content = "",
                        headers = headersOf(HttpHeaders.ETag, "\"part-etag-1\""),
                    )
                    request.method == HttpMethod.Post && request.url.parameters["uploadId"] == "upload-1" -> respond(
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
                    request.method == HttpMethod.Delete && request.url.parameters["uploadId"] == "upload-1" -> respond("")
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
        captured[0].url.parameters.names().contains("uploads").shouldBeTrue()
        captured[1].url.parameters["partNumber"] shouldBeEqualTo "1"
        captured[1].url.parameters["uploadId"] shouldBeEqualTo "upload-1"
        captured[2].url.parameters["uploadId"] shouldBeEqualTo "upload-1"
        captured[3].method shouldBeEqualTo HttpMethod.Delete

        s3.close()
    }

    @Test
    fun `Presign은 S3 query auth와 slash 보존 path를 생성한다`() {
        val s3 = s3Client(endpointOverride = null)

        val presigned = s3.presignGetObject(
            bucket = "demo.bucket",
            key = "logs/2026/app log.txt",
            expires = Duration.ofMinutes(15),
        )

        presigned.method shouldBeEqualTo "GET"
        presigned.url.host shouldBeEqualTo "s3.ap-northeast-2.amazonaws.com"
        presigned.url.encodedPath shouldBeEqualTo "/demo.bucket/logs/2026/app%20log.txt"
        presigned.url.parameters["X-Amz-Algorithm"] shouldBeEqualTo "AWS4-HMAC-SHA256"
        presigned.url.parameters["X-Amz-Expires"] shouldBeEqualTo "900"
        presigned.url.parameters["X-Amz-Signature"].orEmpty().isNotBlank().shouldBeTrue()

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

        captured.method shouldBeEqualTo HttpMethod.Delete
        captured.url.host shouldBeEqualTo "s3.ap-northeast-2.amazonaws.com"
        captured.url.encodedPath shouldBeEqualTo "/demo-bucket/logs/app.txt"

        s3.close()
    }

    @Test
    fun `Presign 만료 시간은 S3 SigV4 허용 범위를 검증한다`() {
        val s3 = s3Client(endpointOverride = null)

        assertFailsWith<IllegalArgumentException> {
            s3.presignGetObject("demo-bucket", "logs/app.txt", Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            s3.presignPutObject("demo-bucket", "logs/app.txt", Duration.ofDays(7).plusSeconds(1))
        }

        s3.close()
    }

    private fun s3Client(
        capture: (HttpRequestData) -> Unit = {},
        response: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { respond("") },
        endpointOverride: Url? = Url("http://localhost:4566"),
        addressingStyle: S3KtorAddressingStyle = S3KtorAddressingStyle.VirtualHosted,
    ): S3KtorClient {
        val credentialsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create("akid", "secret"))
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
        )
    }

    private companion object {
        private val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-05-10T01:02:03Z"), ZoneOffset.UTC)
    }
}
