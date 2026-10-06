package io.bluetape4k.aws.kotlin.dynamodb

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import io.bluetape4k.aws.kotlin.AbstractAwsTest
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.testcontainers.aws.AwsEmulatorServer

abstract class AbstractKotlinDynamoDbTest: AbstractAwsTest() {

    companion object: KLoggingChannel() {
        @JvmStatic
        protected val faker = Fakers.faker

        @JvmStatic
        protected fun randomString(min: Int = 256, max: Int = 2048): String =
            Fakers.randomString(min, max)
    }

    protected suspend inline fun <R> withLocalDynamoDbClient(
        server: AwsEmulatorServer = localStackServer,
        action: suspend (DynamoDbClient) -> R,
    ): R {
        return withDynamoDbClient(
            server.endpointUrl,
            server.region,
            server.credentialsProvider,
            action
        )
    }
}
