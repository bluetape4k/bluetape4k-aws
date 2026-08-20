package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration

class SqsExtendedClientLifecycleTest {

    @Test
    fun `successful stop drains and invokes callback once`() = runTest {
        val client = mockk<SqsExtendedClient>(relaxed = true)
        val properties = SqsExtendedClientProperties(enabled = true, consumerEnabled = true)
        coEvery { client.stopForSpring(any(), any(), any()) } answers {
            (invocation.args[1] as () -> Unit).invoke()
        }
        val lifecycle = SqsExtendedClientLifecycle(client, properties)
        var callbacks = 0

        lifecycle.isAutoStartup() shouldBeEqualTo true
        lifecycle.getPhase() shouldBeEqualTo SqsExtendedClientLifecycle.PHASE
        lifecycle.isRunning() shouldBeEqualTo true
        lifecycle.stop { callbacks++ }
        lifecycle.stop { callbacks++ }

        lifecycle.isRunning() shouldBeEqualTo false
        callbacks shouldBeEqualTo 1
        coVerify(exactly = 1) { client.stopForSpring(any(), any(), any()) }
    }

    @Test
    fun `timeout preserves running state and permits retry`() = runTest {
        val client = mockk<SqsExtendedClient>(relaxed = true)
        val properties = SqsExtendedClientProperties(
            enabled = true,
            consumerEnabled = true,
            shutdownDrainTimeoutSeconds = 1,
        )
        var attempt = 0
        coEvery { client.stopForSpring(any(), any(), any()) } answers {
            if (attempt++ == 0) {
                (invocation.args[2] as (Int) -> Unit).invoke(1)
            } else {
                (invocation.args[1] as () -> Unit).invoke()
            }
        }
        val lifecycle = SqsExtendedClientLifecycle(client, properties)
        var callbacks = 0

        lifecycle.stop { callbacks++ }
        lifecycle.isRunning() shouldBeEqualTo true
        callbacks shouldBeEqualTo 0

        lifecycle.stop { callbacks++ }

        lifecycle.isRunning() shouldBeEqualTo false
        callbacks shouldBeEqualTo 1
        coVerify(exactly = 2) { client.stopForSpring(any(), any(), any()) }
    }

    @Test
    fun `configured drain rejects longer public timeout`() = runTest {
        val client = SqsExtendedClient(
            sqsOperations = mockk(relaxed = true),
            s3Operations = mockk(relaxed = true),
            boundedS3Operations = null,
            s3MetadataOperations = null,
            encryptedS3Operations = null,
            encryptionIdentity = null,
            properties = SqsExtendedClientProperties(
                enabled = true,
                consumerEnabled = true,
                shutdownDrainTimeoutSeconds = 1,
            ),
        )

        assertFailsWith<SqsExtendedConfigurationException> {
            client.drain(Duration.parse("PT2S"))
        }
    }
}
