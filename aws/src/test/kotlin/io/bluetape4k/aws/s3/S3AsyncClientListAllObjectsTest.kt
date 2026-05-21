package io.bluetape4k.aws.s3

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldHaveSize
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response
import software.amazon.awssdk.services.s3.model.S3Object
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

class S3AsyncClientListAllObjectsTest {

    @Test
    fun `listAllObjects follows continuation tokens beyond first S3 page`() = runTest {
        val client = mockk<S3AsyncClient>()
        val requests = mutableListOf<ListObjectsV2Request>()
        val firstPageObjects = (1..1_000).map { s3Object("logs/%04d.txt".format(it)) }

        every {
            client.listObjectsV2(any<Consumer<ListObjectsV2Request.Builder>>())
        } answers {
            val request = captureListObjectsV2Request(firstArg())
            requests += request

            CompletableFuture.completedFuture(
                when (requests.size) {
                    1    -> listObjectsV2Response(
                        contents = firstPageObjects,
                        isTruncated = true,
                        nextContinuationToken = "page-2",
                    )
                    else -> listObjectsV2Response(
                        contents = listOf(s3Object("logs/1001.txt")),
                        isTruncated = false,
                    )
                }
            )
        }

        val objects = client.listAllObjects("demo-bucket", prefix = "logs/").toList()

        objects shouldHaveSize 1_001
        objects.first().key() shouldBeEqualTo "logs/0001.txt"
        objects.last().key() shouldBeEqualTo "logs/1001.txt"

        requests shouldHaveSize 2
        requests[0].bucket() shouldBeEqualTo "demo-bucket"
        requests[0].prefix() shouldBeEqualTo "logs/"
        requests[0].continuationToken().shouldBeNull()
        requests[1].bucket() shouldBeEqualTo "demo-bucket"
        requests[1].prefix() shouldBeEqualTo "logs/"
        requests[1].continuationToken() shouldBeEqualTo "page-2"
        verify(exactly = 2) { client.listObjectsV2(any<Consumer<ListObjectsV2Request.Builder>>()) }
    }

    @Test
    fun `listAllObjects fails when truncated response has no continuation token`() = runTest {
        val client = mockk<S3AsyncClient>()

        every {
            client.listObjectsV2(any<Consumer<ListObjectsV2Request.Builder>>())
        } returns CompletableFuture.completedFuture(
            listObjectsV2Response(
                contents = listOf(s3Object("logs/0001.txt")),
                isTruncated = true,
            )
        )

        assertFailsWith<IllegalStateException> {
            client.listAllObjects("demo-bucket").toList()
        }

        verify(exactly = 1) { client.listObjectsV2(any<Consumer<ListObjectsV2Request.Builder>>()) }
    }

    @Test
    fun `listAllObjects validates bucket name`() {
        val client = mockk<S3AsyncClient>()

        assertFailsWith<IllegalArgumentException> {
            client.listAllObjects("")
        }

        verify(exactly = 0) { client.listObjectsV2(any<Consumer<ListObjectsV2Request.Builder>>()) }
    }

    private fun captureListObjectsV2Request(
        consumer: Consumer<ListObjectsV2Request.Builder>,
    ): ListObjectsV2Request {
        val builder = ListObjectsV2Request.builder()
        consumer.accept(builder)
        return builder.build()
    }

    private fun listObjectsV2Response(
        contents: List<S3Object>,
        isTruncated: Boolean,
        nextContinuationToken: String? = null,
    ): ListObjectsV2Response =
        ListObjectsV2Response.builder()
            .contents(contents)
            .isTruncated(isTruncated)
            .nextContinuationToken(nextContinuationToken)
            .build()

    private fun s3Object(key: String): S3Object =
        S3Object.builder()
            .key(key)
            .build()
}
