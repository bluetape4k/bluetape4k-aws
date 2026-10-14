package io.bluetape4k.aws.kotlin.kms

import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test

class KmsClientSupportTest {

    companion object: KLogging()

    @Test
    fun `kmsClientOf는 null endpoint로 클라이언트를 생성한다`() {
        kmsClientOf(
            endpointUrl = null,
            region = "us-east-1"
        ).use { client ->
            client.config.endpointUrl.shouldBeNull()
            client.config.region shouldBeEqualTo "us-east-1"
        }
    }

    @Test
    fun `kmsClientOf는 region으로 클라이언트를 생성한다`() {
        kmsClientOf(region = "ap-northeast-2").use { client ->
            client.config.region shouldBeEqualTo "ap-northeast-2"
        }
    }

    @Test
    fun `kmsClientOf는 endpoint와 region으로 클라이언트를 생성한다`() {
        kmsClientOf(
            endpointUrl = Url.parse("http://localhost:4566"),
            region = "us-east-1"
        ).use { client ->
            client.config.endpointUrl shouldBeEqualTo Url.parse("http://localhost:4566")
            client.config.region shouldBeEqualTo "us-east-1"
        }
    }

    @Test
    fun `kmsClientOf는 builder 블록으로 추가 설정이 가능하다`() {
        kmsClientOf(region = "us-east-1") {
            // additional config
        }.use { client ->
            with(client.config) {
                log.debug { "endpointUrl=$endpointUrl" }
                log.debug { "region=$region" }
                log.debug { "clientName=$clientName" }
                log.debug { "applicationId=$applicationId" }
            }
        }
    }
}
