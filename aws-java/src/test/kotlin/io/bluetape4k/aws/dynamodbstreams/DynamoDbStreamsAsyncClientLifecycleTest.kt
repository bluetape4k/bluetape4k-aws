package io.bluetape4k.aws.dynamodbstreams

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.aws.AbstractAwsTest
import io.bluetape4k.logging.KLogging
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsAsyncClient

class DynamoDbStreamsAsyncClientLifecycleTest: AbstractAwsTest() {

    companion object: KLogging()

    private val client = mockk<DynamoDbStreamsAsyncClient>(relaxed = true)

    @BeforeEach
    fun beforeEach() {
        clearMocks(client)
    }

    @Test
    fun `factory overload closes client after normal block`() = runTest {
        every { client.close() } just runs

        withDynamoDbStreamsAsyncClient({ client }) {
            it shouldBeSameInstanceAs client
        }

        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `factory overload closes client after block failure`() = runTest {
        every { client.close() } just runs
        val expected = IllegalStateException("boom")

        val actual = assertFailsWith<IllegalStateException> {
            withDynamoDbStreamsAsyncClient<Unit>({ client }) { throw expected }
        }

        actual shouldBeSameInstanceAs expected
        verify(exactly = 1) { client.close() }
    }
}
