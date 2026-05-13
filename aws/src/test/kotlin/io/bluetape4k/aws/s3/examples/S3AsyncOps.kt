package io.bluetape4k.aws.s3.examples

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.aws.s3.AbstractS3Test
import io.bluetape4k.aws.s3.getAsByteArrayAsync
import io.bluetape4k.aws.s3.putAsByteArrayAsync
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.support.toUtf8String
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.RepeatedTest

class S3AsyncOps: AbstractS3Test() {

    companion object: KLoggingChannel() {
        private const val REPEAT_SIZE = 5
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `put object asynchronously`() = runSuspendIO {
        val key = Base58.randomString(16)
        val response = s3AsyncClient
            .putAsByteArrayAsync(
                BUCKET_NAME,
                key,
                randomString().toUtf8Bytes()
            )
            .await()

        log.debug { "Put response=$response" }
        response.eTag().shouldNotBeEmpty()
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `get object asynchronously`() = runSuspendIO {
        val key = randomKey()
        val value = randomString()

        // Put object
        val response = s3AsyncClient
            .putAsByteArrayAsync(
                BUCKET_NAME,
                key,
                value.toUtf8Bytes()
            )
            .await()

        log.debug { "Put response=$response" }

        // Get object
        val content = s3AsyncClient.getAsByteArrayAsync(BUCKET_NAME, key).await()
        content.toUtf8String() shouldBeEqualTo value
    }
}
