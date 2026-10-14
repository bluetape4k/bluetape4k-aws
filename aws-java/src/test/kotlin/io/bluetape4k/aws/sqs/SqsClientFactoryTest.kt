package io.bluetape4k.aws.sqs

import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test

class SqsClientFactoryTest: AbstractSqsTest() {

    companion object: KLogging()

    @Test
    fun `SqsClientFactory Sync create는 queue를 생성하고 삭제할 수 있다`() {
        val sync = SqsClientFactory.Sync.create(
            endpointOverride = localStackServer.endpoint,
            region = localStackServer.region(),
            credentialsProvider = localStackServer.credentialsProvider,
        )
        val queueName = "factory-sync-${Base58.randomString(8).lowercase()}"

        val queueUrl = sync.createQueue(queueName)
        log.debug { "queueUrl=$queueUrl" }
        queueUrl.shouldNotBeEmpty()

        val deleteResponse = sync.deleteQueue(queueUrl)
        log.debug { "deleteResponse=$deleteResponse" }
        deleteResponse.responseMetadata().requestId().shouldNotBeEmpty()
    }

    @Test
    fun `SqsClientFactory Async create는 queue를 생성하고 삭제할 수 있다`() = runSuspendIO {
        val async = SqsClientFactory.Async.create(
            endpointOverride = localStackServer.endpoint,
            region = localStackServer.region(),
            credentialsProvider = localStackServer.credentialsProvider,
        )
        val queueName = "factory-async-${Base58.randomString(8).lowercase()}"

        val queueUrl = async.createQueue(queueName)
        log.debug { "queueUrl=$queueUrl" }
        queueUrl.shouldNotBeEmpty()

        val deleteResponse = async.deleteQueue(queueUrl)
        log.debug { "deleteResponse=$deleteResponse" }
        deleteResponse.responseMetadata().requestId().shouldNotBeEmpty()
    }
}
