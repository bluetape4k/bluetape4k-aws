package io.bluetape4k.aws.kinesis.model

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.aws.core.toSdkBytes
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.SdkBytes
import java.lang.reflect.InvocationTargetException
import kotlin.Function1

class PutRecordTest {

    private val data = "payload".toSdkBytes()

    @Test
    fun `legacy positional overload keeps data as the third argument`() {
        val request = putRecordRequestOf("stream", "partition", data)

        request.streamName() shouldBeEqualTo "stream"
        request.partitionKey() shouldBeEqualTo "partition"
        request.data() shouldBeEqualTo data
        request.explicitHashKey() shouldBeEqualTo null
    }

    @Test
    fun `explicit hash key is additive and validated`() {
        val request = putRecordRequestOf(
            streamName = "stream",
            partitionKey = "partition",
            explicitHashKey = "123",
            data = data,
        )

        request.explicitHashKey() shouldBeEqualTo "123"

        assertFailsWith<IllegalArgumentException> {
            putRecordRequestOf(
                streamName = "stream",
                partitionKey = "partition",
                explicitHashKey = " ",
                data = data,
            )
        }
    }

    @Test
    fun `legacy JVM descriptor remains available`() {
        Class.forName("io.bluetape4k.aws.kinesis.model.PutRecordKt").getDeclaredMethod(
            "putRecordRequestOf",
            String::class.java,
            String::class.java,
            SdkBytes::class.java,
            Function1::class.java,
        )
    }

    @Test
    fun `java null data is rejected before request construction`() {
        val method = Class.forName("io.bluetape4k.aws.kinesis.model.PutRecordKt").getDeclaredMethod(
            "putRecordRequestOf",
            String::class.java,
            String::class.java,
            SdkBytes::class.java,
            Function1::class.java,
        )

        val failure = assertFailsWith<InvocationTargetException> {
            method.invoke(null, "stream", "partition", null, null)
        }
        failure.targetException::class shouldBeEqualTo NullPointerException::class
    }

}
