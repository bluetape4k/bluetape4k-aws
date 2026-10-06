package io.bluetape4k.aws.kotlin.kinesis

import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.closeSafe
import org.junit.jupiter.api.Test

class KinesisClientSupportTest {

    companion object: KLogging()

    @Test
    fun `kinesisClientOf는 null endpoint로 클라이언트를 생성한다`() {
        val client = kinesisClientOf(
            endpointUrl = null,
            region = "us-east-1"
        )
        client.config.region shouldBeEqualTo "us-east-1"
        client.closeSafe()
    }

    @Test
    fun `kinesisClientOf는 region으로 클라이언트를 생성한다`() {
        val client = kinesisClientOf(region = "ap-northeast-2")
        client.config.region shouldBeEqualTo "ap-northeast-2"
        client.closeSafe()
    }

    @Test
    fun `kinesisClientOf는 endpoint와 region으로 클라이언트를 생성한다`() {
        val client = kinesisClientOf(
            endpointUrl = Url.parse("http://localhost:4566"),
            region = "us-east-1"
        )
        client.config.endpointUrl shouldBeEqualTo Url.parse("http://localhost:4566")
        client.config.region shouldBeEqualTo "us-east-1"
        client.closeSafe()
    }
}
