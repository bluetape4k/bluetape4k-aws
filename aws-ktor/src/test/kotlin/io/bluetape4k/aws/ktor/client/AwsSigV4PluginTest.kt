package io.bluetape4k.aws.ktor.client

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.test.runTest
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AwsSigV4PluginTest {

    @Test
    fun `헤더 인증으로 요청에 Authorization 헤더를 추가한다`() = runTest {
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
    fun `세션 자격 증명은 보안 토큰 헤더를 추가한다`() = runTest {
        var token: String? = null

        val client = signedClient(
            credentialsProvider = StaticCredentialsProvider.create(
                AwsSessionCredentials.create("akid", "secret", "session-token")
            )
        ) {
            token = it.headers["X-Amz-Security-Token"]
        }

        client.get("https://api.example.com/prod/orders")

        token shouldBeEqualTo "session-token"
        client.close()
    }

    @Test
    fun `쿼리 문자열 인증은 X-Amz 서명 파라미터를 추가한다`() = runTest {
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
    fun `ByteArrayContent body는 payload 서명을 허용한다`() = runTest {
        var authorization: String? = null

        val client = signedClient {
            authorization = it.headers[HttpHeaders.Authorization]
        }

        client.post("https://api.example.com/prod/orders") {
            setBody("""{"id":"order-1"}""".encodeToByteArray())
        }

        authorization.shouldNotBeNull()
        authorization shouldContain "AWS4-HMAC-SHA256"
        client.close()
    }

    @Test
    fun `스트리밍 body는 payload 서명 활성화 시 실패한다`() = runTest {
        val client = signedClient()

        assertFailsWith<IllegalStateException> {
            client.post("https://api.example.com/prod/orders") {
                setBody(
                    object : OutgoingContent.WriteChannelContent() {
                        override suspend fun writeTo(channel: ByteWriteChannel) {
                        }
                    }
                )
            }
        }

        client.close()
    }

    @Test
    fun `스트리밍 body는 payload 서명 비활성화 시 허용한다`() = runTest {
        var authorization: String? = null
        val client = signedClient(payloadSigningEnabled = false) {
            authorization = it.headers[HttpHeaders.Authorization]
        }

        client.post("https://api.example.com/prod/orders") {
            setBody(
                object : OutgoingContent.WriteChannelContent() {
                    override suspend fun writeTo(channel: ByteWriteChannel) {
                    }
                }
            )
        }

        authorization.shouldNotBeNull()
        authorization shouldContain "AWS4-HMAC-SHA256"
        client.close()
    }

    @Test
    fun `region은 빈 문자열일 수 없다`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            HttpClient(MockEngine) {
                engine {
                    addHandler { respondOk() }
                }
                install(AwsSigV4Plugin) {
                    region = ""
                    service = "execute-api"
                }
            }.get("https://api.example.com/prod/orders")
        }
    }

    private fun signedClient(
        credentialsProvider: StaticCredentialsProvider = StaticCredentialsProvider.create(
            software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("akid", "secret")
        ),
        authLocation: AwsSigV4AuthLocation = AwsSigV4AuthLocation.Header,
        payloadSigningEnabled: Boolean = true,
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
            }
        }
    }
}
