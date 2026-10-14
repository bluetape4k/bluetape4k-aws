package io.bluetape4k.aws.kotlin.ses

import aws.sdk.kotlin.services.ses.SesClient
import io.bluetape4k.aws.kotlin.AbstractAwsTest
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.testcontainers.aws.AwsEmulatorServer

abstract class AbstractKotlinSesTest: AbstractAwsTest() {

    companion object: KLoggingChannel() {
        @JvmStatic
        protected val faker = Fakers.faker

        @JvmStatic
        protected fun randomString(min: Int = 256, max: Int = 2048): String {
            return Fakers.randomString(min, max)
        }

        const val domain = "example.com"
        const val senderEmail = "from-user@example.com"
        const val receiverEmail = "to-use@example.com"
    }

    protected suspend fun <R> withTestSesClient(
        awsServer: AwsEmulatorServer,
        block: suspend (SesClient) -> R,
    ): R =
        withSesClient(
            awsServer.endpointUrl,
            awsServer.region,
            awsServer.credentialsProvider,
        ) { client ->
            block(client)
        }
}
