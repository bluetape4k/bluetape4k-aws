package io.bluetape4k.aws.examples.ktor.s3

import io.bluetape4k.aws.ktor.s3.S3KtorAddressingStyle
import io.bluetape4k.aws.ktor.s3.S3KtorClient
import io.bluetape4k.assertions.shouldBeEqualTo
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider

class S3KtorServerExamplesTest {

    @Test
    fun `Ktor routes upload download presign list and delete through S3KtorClient`() = testApplication {
        val s3 = s3ClientWithMockEngine()
        application {
            s3KtorExampleModule(s3, bucket = "demo-bucket")
        }

        try {
            client.put("/s3/objects/docs/hello.txt") {
                headers.append(HttpHeaders.ContentType, "text/plain")
                setBody("hello ktor s3")
            }.status shouldBeEqualTo HttpStatusCode.OK

            client.get("/s3/objects/docs/hello.txt").bodyAsText() shouldBeEqualTo "hello ktor s3"
            client.get("/s3/objects/docs/hello.txt/stream").bodyAsText() shouldBeEqualTo "hello ktor s3"
            client.get("/s3/objects?prefix=docs/").status shouldBeEqualTo HttpStatusCode.OK
            client.put("/s3/detected-objects/docs/data.json") {
                setBody("""{"enabled":true}""")
            }.status shouldBeEqualTo HttpStatusCode.OK
            client.put("/s3/config/config/application.conf") {
                setBody("ktor { deployment { port = 8080 } }")
            }.status shouldBeEqualTo HttpStatusCode.OK
            client.get("/s3/config/config/application.conf")
                .bodyAsText() shouldBeEqualTo "ktor { deployment { port = 8080 } }"
            client.get("/s3/presigned-get/docs/hello.txt").bodyAsText().contains("X-Amz-Algorithm") shouldBeEqualTo true
            client.get("/s3/presigned-put/docs/hello.txt").bodyAsText().contains("X-Amz-Algorithm") shouldBeEqualTo true
            client.delete("/s3/objects/docs/hello.txt").status shouldBeEqualTo HttpStatusCode.NoContent
        } finally {
            s3.close()
        }
    }

    private fun s3ClientWithMockEngine(): S3KtorClient {
        val engine = MockEngine { request ->
            when (request.method.value) {
                "PUT" -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ETag, "\"demo-etag\""),
                    )
                }

                "GET" -> {
                    if (request.url.parameters["list-type"] == "2") {
                        respond(
                            content = """
                                <ListBucketResult>
                                    <Name>demo-bucket</Name>
                                    <Prefix>docs/</Prefix>
                                    <KeyCount>1</KeyCount>
                                    <IsTruncated>false</IsTruncated>
                                    <Contents>
                                        <Key>docs/hello.txt</Key>
                                        <ETag>"demo-etag"</ETag>
                                        <Size>13</Size>
                                    </Contents>
                                </ListBucketResult>
                            """.trimIndent(),
                            status = HttpStatusCode.OK,
                        )
                    } else if (request.url.encodedPath.endsWith("/config/application.conf")) {
                        respond(
                            content = "ktor { deployment { port = 8080 } }",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/plain; charset=utf-8"),
                        )
                    } else {
                        respond(
                            content = "hello ktor s3",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                        )
                    }
                }

                "DELETE" -> respond("", HttpStatusCode.NoContent)
                else -> respond("", HttpStatusCode.BadRequest)
            }
        }

        return S3KtorClient(
            httpClient = HttpClient(engine),
            region = "ap-northeast-2",
            credentialsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")),
            endpointOverride = Url("http://localhost:4566"),
            addressingStyle = S3KtorAddressingStyle.Path,
        )
    }
}
