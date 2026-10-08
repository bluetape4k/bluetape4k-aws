package io.bluetape4k.aws.kotlin.sts

import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.aws.kotlin.http.HttpClientEngineProvider
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

class StsClientSupportTest {

    companion object: KLogging()

    @Test
    fun `stsClientOf는 null endpoint로 클라이언트를 생성한다`() {
        val client = stsClientOf(
            endpointUrl = null,
            region = "us-east-1"
        )
        client.close()
    }

    @Test
    fun `stsClientOf는 region으로 클라이언트를 생성한다`() {
        val client = stsClientOf(region = "ap-northeast-2")
        client.close()
    }

    @Test
    fun `stsClientOf는 endpoint와 region으로 클라이언트를 생성한다`() {
        val client = stsClientOf(
            endpointUrl = Url.parse("http://localhost:4566"),
            region = "us-east-1"
        )
        client.config.endpointUrl shouldBeEqualTo Url.parse("http://localhost:4566")
        client.config.region shouldBeEqualTo "us-east-1"
        client.close()
    }

    @Test
    fun `stsClientOf는 builder 블록으로 추가 설정이 가능하다`() {
        val client = stsClientOf(region = "us-east-1") {
            // additional config
            httpClient = HttpClientEngineProvider.Crt.httpEngine
        }
        client.config.region shouldBeEqualTo "us-east-1"
        client.config.httpClient shouldBeSameInstanceAs HttpClientEngineProvider.Crt.httpEngine
        client.close()
    }
}
