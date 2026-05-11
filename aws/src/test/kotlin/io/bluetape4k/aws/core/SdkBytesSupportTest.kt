package io.bluetape4k.aws.core

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class SdkBytesSupportTest {

    @Test
    fun `String toSdkBytes는 UTF-8 문자열을 보존한다`() {
        val value = "안녕하세요 aws"
        val sdkBytes = value.toSdkBytes()

        sdkBytes.asUtf8String() shouldBeEqualTo value
    }

    @Test
    fun `ByteArray toSdkBytes는 바이트 배열을 보존한다`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val sdkBytes = bytes.toSdkBytes()

        sdkBytes.asByteArray().toList() shouldBeEqualTo bytes.toList()
    }

    @Test
    fun `ByteArray toSdkBytes는 원본 배열 변경과 분리된 스냅샷을 만든다`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val sdkBytes = bytes.toSdkBytes()

        bytes[0] = 9

        sdkBytes.asByteArray().toList() shouldBeEqualTo listOf(1.toByte(), 2.toByte(), 3.toByte(), 4.toByte())
    }

    @Test
    fun `ByteArray toSdkBytesUnsafe는 원본 배열 참조를 공유한다`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val sdkBytes = bytes.toSdkBytesUnsafe()

        bytes[0] = 9

        sdkBytes.asByteArrayUnsafe().toList() shouldBeEqualTo listOf(9.toByte(), 2.toByte(), 3.toByte(), 4.toByte())
    }

    @Test
    fun `String toUtf8SdkBytes는 UTF-8 문자열을 보존한다`() {
        val value = "hello aws"
        val sdkBytes = value.toUtf8SdkBytes()

        sdkBytes.asUtf8String() shouldBeEqualTo value
    }

    @Test
    fun `InputStream toSdkBytes는 스트림 내용을 읽어 보존한다`() {
        val sdkBytes = "stream-data".byteInputStream().toSdkBytes()

        sdkBytes.asUtf8String() shouldBeEqualTo "stream-data"
    }

    @Test
    fun `ByteBuffer toSdkBytes는 버퍼 내용을 보존한다`() {
        val buffer = ByteBuffer.wrap(byteArrayOf(7, 8, 9))
        val sdkBytes = buffer.toSdkBytes()

        sdkBytes.asByteArray().toList() shouldBeEqualTo listOf(7.toByte(), 8.toByte(), 9.toByte())
    }

    @Test
    fun `ByteBuffer toSdkBytes는 position부터 limit까지 변환한다`() {
        val buffer = ByteBuffer.wrap(byteArrayOf(7, 8, 9, 10)).apply {
            position(1)
            limit(3)
        }
        val sdkBytes = buffer.toSdkBytes()

        sdkBytes.asByteArray().toList() shouldBeEqualTo listOf(8.toByte(), 9.toByte())
    }
}
