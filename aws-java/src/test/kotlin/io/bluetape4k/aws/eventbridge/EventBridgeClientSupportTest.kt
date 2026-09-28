package io.bluetape4k.aws.eventbridge

import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.aws.AbstractAwsTest
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import java.net.URI

class EventBridgeClientSupportTest: AbstractAwsTest() {

    companion object: KLogging()

    @Test
    fun `eventBridgeClientOf creates closeable sync client`() {
        val client = eventBridgeClientOf(
            endpoint = URI("http://localhost:4566"),
            region = Region.US_EAST_1,
            credentialsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")),
        )

        log.debug { "client=$client" }
        client.shouldNotBeNull()
        client.close()
    }

    @Test
    fun `eventBridgeAsyncClientOf creates closeable async client`() {
        val client = eventBridgeAsyncClientOf(
            endpoint = URI("http://localhost:4566"),
            region = Region.US_EAST_1,
            credentialsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")),
        )

        log.debug { "client=$client" }
        client.shouldNotBeNull()
        client.close()
    }
}
