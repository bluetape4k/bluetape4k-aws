package io.bluetape4k.aws.kotlin.ses

import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.assertions.shouldBe
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.aws.kotlin.http.HttpClientEngineProvider
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

class SesClientSupportTest {

    companion object: KLogging()

    @Test
    fun `sesClientOf는 null endpoint로 클라이언트를 생성한다`() {
        sesClientOf(
            endpointUrl = null,
            region = "us-east-1"
        ).use { client ->
            client.config.endpointUrl.shouldBeNull()
            client.config.region shouldBe "us-east-1"
        }
    }

    @Test
    fun `sesClientOf는 region으로 클라이언트를 생성한다`() {
        sesClientOf(region = "ap-northeast-2").use { client ->
            client.config.endpointUrl.shouldBeNull()
            client.config.region shouldBeEqualTo "ap-northeast-2"
        }
    }

    @Test
    fun `sesClientOf는 endpoint와 region으로 클라이언트를 생성한다`() {
        sesClientOf(
            endpointUrl = Url.parse("http://localhost:4566"),
            region = "us-east-1"
        ).use { client ->
            client.config.endpointUrl shouldBeEqualTo Url.parse("http://localhost:4566")
            client.config.region shouldBeEqualTo "us-east-1"
        }
    }

    @Test
    fun `sesClientOf는 builder 블록으로 추가 설정이 가능하다`() {
        sesClientOf(region = "us-east-1") {
            // additional config
            this.httpClient = HttpClientEngineProvider.Crt.httpEngine
        }.use { client ->
            client.config.endpointUrl.shouldBeNull()
            client.config.region shouldBeEqualTo "us-east-1"
            client.config.httpClient shouldBe HttpClientEngineProvider.Crt.httpEngine
        }
    }
}
