package io.bluetape4k.aws.ktor

import aws.smithy.kotlin.runtime.client.ProtocolRequestInterceptorContext
import aws.smithy.kotlin.runtime.client.RequestInterceptorContext
import aws.smithy.kotlin.runtime.http.HttpMethod
import aws.smithy.kotlin.runtime.http.request.HttpRequest
import aws.smithy.kotlin.runtime.net.Host
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class AwsKtorEndpointPolicyTest {

    @Test
    fun `allows HTTPS endpoint with region`() {
        validateAwsKotlinServiceEndpoint(
            endpoint = "https://service.example.com:8443/prefix",
            region = "ap-northeast-2",
            allowInsecureLocalHttpEndpoint = false,
        )
    }

    @Test
    fun `requires region when endpoint is configured`() {
        assertFailsWith<IllegalArgumentException> {
            validateAwsKotlinServiceEndpoint(
                endpoint = "https://service.example.com",
                region = null,
                allowInsecureLocalHttpEndpoint = false,
            )
        }
    }

    @Test
    fun `rejects remote HTTP and non HTTP schemes`() {
        listOf("http://service.example.com", "ftp://localhost/resource").forEach { endpoint ->
            assertFailsWith<IllegalArgumentException> {
                validateAwsKotlinServiceEndpoint(
                    endpoint = endpoint,
                    region = "ap-northeast-2",
                    allowInsecureLocalHttpEndpoint = true,
                )
            }
        }
    }

    @Test
    fun `rejects loopback HTTP without explicit opt in`() {
        assertFailsWith<IllegalArgumentException> {
            validateAwsKotlinServiceEndpoint(
                endpoint = "http://localhost:4566",
                region = "ap-northeast-2",
                allowInsecureLocalHttpEndpoint = false,
            )
        }
    }

    @Test
    fun `allows only localhost and literal loopback HTTP with opt in`() {
        listOf(
            "http://localhost:4566/path",
            "http://LOCALHOST:4566/path",
            "http://127.0.0.1:4566/path",
            "http://127.255.255.254:4566/path",
            "http://[::1]:4566/path",
        ).forEach { endpoint ->
            validateAwsKotlinServiceEndpoint(
                endpoint = endpoint,
                region = "ap-northeast-2",
                allowInsecureLocalHttpEndpoint = true,
            )
        }
    }

    @Test
    fun `rejects non loopback hosts and ambiguous IPv4 spellings even with opt in`() {
        listOf(
            "http://localhost.example.com:4566",
            "http://127.0.0.1.example.com:4566",
            "http://0177.0.0.1:4566",
            "http://127.000.0.1:4566",
            "http://[::1%25lo0]:4566",
        ).forEach { endpoint ->
            assertFailsWith<IllegalArgumentException> {
                validateAwsKotlinServiceEndpoint(
                    endpoint = endpoint,
                    region = "ap-northeast-2",
                    allowInsecureLocalHttpEndpoint = true,
                )
            }
        }
    }

    @Test
    fun `rejects relative or malformed endpoint`() {
        listOf("/local", "http://", "http://user:password@localhost:4566").forEach { endpoint ->
            assertFailsWith<IllegalArgumentException> {
                validateAwsKotlinServiceEndpoint(
                    endpoint = endpoint,
                    region = "ap-northeast-2",
                    allowInsecureLocalHttpEndpoint = true,
                )
            }
        }
    }

    @Test
    fun `rejects configured remote HTTP endpoint before request execution`() {
        val interceptor = AwsKotlinEndpointPolicyInterceptor(
            configuredEndpoint = "http://169.254.169.254/latest/meta-data",
            region = "ap-northeast-2",
            allowInsecureLocalHttpEndpoint = true,
        )

        assertFailsWith<IllegalArgumentException> {
            interceptor.readBeforeExecution(mockk<RequestInterceptorContext<Any>>(relaxed = true))
        }
    }

    @Test
    fun `rejects remote HTTP endpoint after endpoint discovery rewrites the request host before signing`() {
        val discoveredUrl = Url.parse("http://localhost:4566").copy {
            host = Host.parse("169.254.169.254")
        }
        val request = HttpRequest(
            method = HttpMethod.GET,
            url = discoveredUrl,
        )
        val context = mockk<ProtocolRequestInterceptorContext<Any, HttpRequest>> {
            every { protocolRequest } returns request
        }

        assertFailsWith<IllegalArgumentException> {
            runSuspendIO {
                AwsKotlinEndpointPolicyInterceptor(
                    configuredEndpoint = null,
                    region = null,
                    allowInsecureLocalHttpEndpoint = true,
                ).modifyBeforeSigning(context)
            }
        }
    }
}
