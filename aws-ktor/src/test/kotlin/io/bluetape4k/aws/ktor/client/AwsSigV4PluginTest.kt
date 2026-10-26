package io.bluetape4k.aws.ktor.client

import aws.smithy.kotlin.runtime.auth.awssigning.AwsSignatureType
import aws.smithy.kotlin.runtime.auth.awssigning.AwsSigner
import aws.smithy.kotlin.runtime.auth.awssigning.AwsSigningConfig
import aws.smithy.kotlin.runtime.auth.awssigning.AwsSigningResult
import aws.smithy.kotlin.runtime.auth.awssigning.DefaultAwsSigner
import aws.smithy.kotlin.runtime.auth.awssigning.HashSpecification
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.collections.Attributes
import aws.smithy.kotlin.runtime.http.HttpBody
import aws.smithy.kotlin.runtime.http.HttpMethod as SmithyHttpMethod
import aws.smithy.kotlin.runtime.http.request.HttpRequest as SmithyHttpRequest
import aws.smithy.kotlin.runtime.http.request.headers
import aws.smithy.kotlin.runtime.http.request.url
import aws.smithy.kotlin.runtime.net.url.Url as SmithyUrl
import aws.smithy.kotlin.runtime.time.Instant as SmithyInstant
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds

class AwsSigV4PluginTest {

    @Test
    fun `공개 AwsSigner API가 고정된 S3 query signing vector를 생성한다`() = runSuspendIO {
        val result = DefaultAwsSigner.sign(
            SmithyHttpRequest {
                method = SmithyHttpMethod.GET
                url(SmithyUrl.parse("https://s3.ap-northeast-2.amazonaws.com/demo.bucket/logs/2026/app%20log.txt"))
                headers { append("Host", "s3.ap-northeast-2.amazonaws.com") }
            },
            AwsSigningConfig {
                credentials = Credentials("akid", "secret")
                region = "ap-northeast-2"
                service = "s3"
                signingDate = SmithyInstant.fromIso8601("2026-05-10T01:02:03Z")
                signatureType = AwsSignatureType.HTTP_REQUEST_VIA_QUERY_PARAMS
                expiresAfter = 900.seconds
                hashSpecification = HashSpecification.UnsignedPayload
                useDoubleUriEncode = false
                normalizeUriPath = false
            },
        )

        result.output.url.toString() shouldContain "X-Amz-Expires=900"
        result.output.url.toString() shouldContain "X-Amz-SignedHeaders=host"
        result.output.url.toString() shouldContain "X-Amz-Signature="
        Regex("[?&]X-Amz-Signature=([^&]+)")
            .find(result.output.url.toString())
            ?.groupValues
            ?.get(1) shouldBeEqualTo "3db75a0ad67e1d88633145f9c3ffca7556f32c68680f5071e6e2933738adfcbd"
    }

    @Test
    fun `헤더 인증으로 요청에 Authorization 헤더를 추가한다`() = runSuspendIO {
        var authorization: String? = null
        var amzDate: String? = null

        val client = signedClient {
            authorization = it.headers[HttpHeaders.Authorization]
            amzDate = it.headers["X-Amz-Date"]
        }

        client.get("https://api.example.com/prod/orders?status=open")

        authorization.shouldNotBeNull()
        authorization shouldContain "AWS4-HMAC-SHA256"
        authorization shouldContain "Credential=akid/20260510/ap-northeast-2/execute-api/aws4_request"
        authorization shouldContain "SignedHeaders=accept;host;x-amz-content-sha256;x-amz-date"
        amzDate shouldBeEqualTo "20260510T010203Z"
        client.close()
    }

    @Test
    fun `세션 자격 증명은 보안 토큰 헤더를 추가한다`() = runSuspendIO {
        var token: String? = null

        val client = signedClient(
            credentialsProvider = testCredentialsProvider(sessionToken = "session-token")
        ) {
            token = it.headers["X-Amz-Security-Token"]
        }

        client.get("https://api.example.com/prod/orders")

        token shouldBeEqualTo "session-token"
        client.close()
    }

    @Test
    fun `자격 증명 공급자는 각 요청마다 다시 resolve한다`() = runSuspendIO {
        var resolves = 0
        val authorizations = mutableListOf<String>()
        val tokens = mutableListOf<String>()
        val provider = object: CredentialsProvider {
            override suspend fun resolve(attributes: Attributes): Credentials {
                resolves += 1
                return Credentials("akid-$resolves", "secret", "session-$resolves")
            }
        }
        val client = signedClient(credentialsProvider = provider) {
            authorizations += it.headers[HttpHeaders.Authorization].orEmpty()
            tokens += it.headers["X-Amz-Security-Token"].orEmpty()
        }

        client.get("https://api.example.com/prod/orders/1")
        client.get("https://api.example.com/prod/orders/2")

        resolves shouldBeEqualTo 2
        authorizations[0] shouldContain "Credential=akid-1/"
        authorizations[1] shouldContain "Credential=akid-2/"
        tokens shouldBeEqualTo listOf("session-1", "session-2")
        client.close()
    }

    @Test
    fun `서명 입력과 전송된 URI 헤더 본문이 같은 signed request를 사용한다`() = runSuspendIO {
        val signer = RecordingSigner()
        lateinit var captured: io.ktor.client.request.HttpRequestData
        val client = signedClient(signer = signer, authLocation = AwsSigV4AuthLocation.QueryString) {
            captured = it
        }
        val payload = "signed payload".encodeToByteArray()

        client.post(
            "https://s3.ap-northeast-2.amazonaws.com:8443/demo.bucket/a%2Fb/" +
                "app%20log.txt?dup=1&dup=&flag&empty=&encoded=a%2Fb",
        ) {
            headers {
                append("X-Duplicate", "first")
                append("X-Duplicate", "second")
            }
            setBody(payload)
        }

        val signedInput = signer.input.shouldNotBeNull()
        val signedOutput = signer.output.shouldNotBeNull()
        captured.url.toString() shouldBeEqualTo signedOutput.url.toString()
        captured.url.host shouldBeEqualTo "s3.ap-northeast-2.amazonaws.com"
        captured.url.port shouldBeEqualTo 8443
        captured.url.encodedPath shouldBeEqualTo "/demo.bucket/a%2Fb/app%20log.txt"
        captured.url.parameters.getAll("dup") shouldBeEqualTo listOf("1", "")
        captured.url.parameters["flag"] shouldBeEqualTo ""
        captured.url.parameters["empty"] shouldBeEqualTo ""
        signedInput.headers.getAll("x-duplicate") shouldBeEqualTo listOf("first", "second")
        captured.headers.getAll("X-Duplicate") shouldBeEqualTo signedOutput.headers.getAll("x-duplicate")
        captured.body.toByteArray() shouldBeEqualTo (signedInput.body as HttpBody.Bytes).bytes()
        client.close()
    }

    @Test
    fun `원격 HTTP endpoint는 credentials resolve 전에 거부한다`() = runSuspendIO {
        var resolves = 0
        var sends = 0
        val provider = object: CredentialsProvider {
            override suspend fun resolve(attributes: Attributes): Credentials {
                resolves += 1
                return Credentials("akid", "secret")
            }
        }
        val client = signedClient(credentialsProvider = provider) { sends += 1 }

        assertFailsWith<IllegalArgumentException> {
            client.get("http://aws.example.com/orders")
        }

        resolves shouldBeEqualTo 0
        sends shouldBeEqualTo 0
        client.close()
    }

    @Test
    fun `loopback HTTP endpoint는 명시적으로 허용할 수 있다`() = runSuspendIO {
        var sends = 0
        val client = signedClient(allowInsecureLocalHttpEndpoint = true) { sends += 1 }

        client.get("http://127.0.0.1:4566/orders")

        sends shouldBeEqualTo 1
        client.close()
    }

    @Test
    fun `쿼리 문자열 인증은 X-Amz 서명 파라미터를 추가한다`() = runSuspendIO {
        var query: Map<String, List<String>> = emptyMap()

        val client = signedClient(authLocation = AwsSigV4AuthLocation.QueryString) {
            query = it.url.parameters.entries().associate { entry -> entry.key to entry.value }
        }

        client.get("https://api.example.com/prod/orders?status=open")

        query.keys shouldContain "X-Amz-Algorithm"
        query.keys shouldContain "X-Amz-Credential"
        query.keys shouldContain "X-Amz-Date"
        query.keys shouldContain "X-Amz-Signature"
        query["status"] shouldBeEqualTo listOf("open")
        client.close()
    }

    @Test
    fun `ByteArrayContent body는 payload 서명을 허용한다`() = runSuspendIO {
        var authorization: String? = null
        var payloadHash: String? = null

        val client = signedClient {
            authorization = it.headers[HttpHeaders.Authorization]
            payloadHash = it.headers["X-Amz-Content-Sha256"]
        }

        val payload = """{"id":"order-1"}""".encodeToByteArray()
        client.post("https://api.example.com/prod/orders") {
            setBody(payload)
        }

        authorization.shouldNotBeNull()
        authorization shouldContain "AWS4-HMAC-SHA256"
        payloadHash shouldBeEqualTo payload.sha256Hex()
        client.close()
    }

    @Test
    fun `스트리밍 body는 payload 서명 활성화 시 실패한다`() = runSuspendIO {
        var resolves = 0
        var sends = 0
        val provider = object: CredentialsProvider {
            override suspend fun resolve(attributes: Attributes): Credentials {
                resolves += 1
                return Credentials("akid", "secret")
            }
        }
        val client = signedClient(credentialsProvider = provider) { sends += 1 }

        assertFailsWith<IllegalStateException> {
            client.post("https://api.example.com/prod/orders") {
                setBody(
                    object: OutgoingContent.WriteChannelContent() {
                        override suspend fun writeTo(channel: ByteWriteChannel) {
                        }
                    }
                )
            }
        }

        resolves shouldBeEqualTo 0
        sends shouldBeEqualTo 0
        client.close()
    }

    @Test
    fun `스트리밍 body는 payload 서명 비활성화 시 허용한다`() = runSuspendIO {
        var authorization: String? = null
        var payloadHash: String? = null
        var bodyWrites = 0
        var capturedBody: OutgoingContent? = null
        val client = signedClient(payloadSigningEnabled = false) {
            authorization = it.headers[HttpHeaders.Authorization]
            payloadHash = it.headers["X-Amz-Content-Sha256"]
            capturedBody = it.body as? OutgoingContent
        }

        client.post("https://api.example.com/prod/orders") {
            setBody(
                object: OutgoingContent.WriteChannelContent() {
                    override suspend fun writeTo(channel: ByteWriteChannel) {
                            bodyWrites += 1
                    }
                }
            )
        }

        authorization.shouldNotBeNull()
        authorization shouldContain "AWS4-HMAC-SHA256"
        payloadHash shouldBeEqualTo "UNSIGNED-PAYLOAD"
        bodyWrites shouldBeEqualTo 0
        capturedBody.shouldNotBeNull()
        client.close()
    }

    @Test
    fun `region은 빈 문자열일 수 없다`() = runSuspendIO {
        assertFailsWith<IllegalArgumentException> {
            HttpClient(MockEngine) {
                engine {
                    addHandler { respondOk() }
                }
                install(AwsSigV4Plugin) {
                    region = ""
                    service = "execute-api"
                    credentialsProvider = testCredentialsProvider()
                }
            }.get("https://api.example.com/prod/orders")
        }
    }

    @Test
    fun `standalone plugin은 caller credentials provider를 요구한다`() {
        assertFailsWith<IllegalArgumentException> {
            HttpClient(MockEngine) {
                engine { addHandler { respondOk() } }
                install(AwsSigV4Plugin) {
                    region = "ap-northeast-2"
                    service = "execute-api"
                }
            }
        }
    }

    private fun signedClient(
        credentialsProvider: CredentialsProvider = testCredentialsProvider(),
        authLocation: AwsSigV4AuthLocation = AwsSigV4AuthLocation.Header,
        payloadSigningEnabled: Boolean = true,
        allowInsecureLocalHttpEndpoint: Boolean = false,
        signer: AwsSigner = DefaultAwsSigner,
        capture: (io.ktor.client.request.HttpRequestData) -> Unit = {},
    ): HttpClient {
        return HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    capture(request)
                    respondOk()
                }
            }
            install(AwsSigV4Plugin) {
                region = "ap-northeast-2"
                service = "execute-api"
                this.credentialsProvider = credentialsProvider
                this.authLocation = authLocation
                this.payloadSigningEnabled = payloadSigningEnabled
                signingClock = Clock.fixed(Instant.parse("2026-05-10T01:02:03Z"), ZoneOffset.UTC)
                this.allowInsecureLocalHttpEndpoint = allowInsecureLocalHttpEndpoint
                this.signer = signer
            }
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

    private class RecordingSigner: AwsSigner by DefaultAwsSigner {
        var input: SmithyHttpRequest? = null
            private set
        var output: SmithyHttpRequest? = null
            private set

        override suspend fun sign(
            request: SmithyHttpRequest,
            config: AwsSigningConfig,
        ): AwsSigningResult<SmithyHttpRequest> {
            input = request
            return DefaultAwsSigner.sign(request, config).also { output = it.output }
        }
    }

    private fun OutgoingContent.toByteArray(): ByteArray =
        when (this) {
            is OutgoingContent.ByteArrayContent -> bytes()
            else -> error("Unsupported test content: ${this::class.qualifiedName}")
        }

    private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
