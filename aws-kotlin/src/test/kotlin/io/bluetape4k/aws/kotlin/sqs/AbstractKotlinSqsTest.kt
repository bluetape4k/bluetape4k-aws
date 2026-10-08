package io.bluetape4k.aws.kotlin.sqs

import aws.sdk.kotlin.services.sqs.SqsClient
import io.bluetape4k.aws.kotlin.AbstractAwsTest
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.testcontainers.aws.AwsEmulatorServer

abstract class AbstractKotlinSqsTest: AbstractAwsTest() {

    companion object: KLoggingChannel() {
        @JvmStatic
        protected val faker = Fakers.faker

        @JvmStatic
        protected fun randomString(min: Int = 256, max: Int = 2048): String {
            return Fakers.randomString(min, max)
        }
    }

    protected suspend inline fun <R> withTestSqsClient(
        awsServer: AwsEmulatorServer,
        crossinline action: suspend (SqsClient) -> R,
    ): R {
        return withSqsClient(
            awsServer.endpointUrl,
            awsServer.region,
            awsServer.credentialsProvider,
        ) { client ->
            action(client)
        }
    }
}
