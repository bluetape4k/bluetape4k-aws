package io.bluetape4k.aws.kotlin.sns

import aws.sdk.kotlin.services.sns.SnsClient
import io.bluetape4k.aws.kotlin.AbstractAwsTest
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.testcontainers.aws.AwsEmulatorServer

abstract class AbstractKotlinSnsTest: AbstractAwsTest() {

    companion object: KLoggingChannel() {
        @JvmStatic
        protected val faker = Fakers.faker

        @JvmStatic
        protected fun randomString(min: Int = 256, max: Int = 2048): String {
            return Fakers.randomString(min, max)
        }
    }

    protected suspend inline fun <R> withTestSnsClient(
        awsServer: AwsEmulatorServer,
        crossinline action: suspend (SnsClient) -> R,
    ): R =
        withSnsClient(
            localStackServer.endpointUrl,
            localStackServer.region,
            localStackServer.credentialsProvider,
        ) { client ->
            action(client)
        }
}
