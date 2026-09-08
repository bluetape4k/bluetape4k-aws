package io.bluetape4k.aws.spring.kms

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.Base64

class KmsAdapterPlaintextLifetimeTest {

    @Test
    fun `text adapter clears returned plaintext after decoding`() {
        val plaintext = "민감한 평문".toByteArray()
        val kms = mockk<KmsOperations>()
        coEvery { kms.decrypt(any(), any(), any()) } returns plaintext
        val encrypted = Base64.getEncoder().encodeToString(byteArrayOf(1))

        KmsTextEncryptor(kms).decrypt(encrypted) shouldBeEqualTo "민감한 평문"

        plaintext.all { it == 0.toByte() }.shouldBeTrue()
    }

    @Test
    fun `field codec clears returned plaintext after decoding`() = runSuspendIO {
        val plaintext = "민감한 필드".toByteArray()
        val kms = mockk<KmsOperations>()
        coEvery { kms.decrypt(any(), any(), any()) } returns plaintext
        val annotation = FieldFixture::class.java.getDeclaredField("value").getAnnotation(KmsEncrypted::class.java)

        KmsEncryptedFieldCodec(kms).decrypt("b4k-kms:v1:AQ", annotation) shouldBeEqualTo "민감한 필드"

        plaintext.all { it == 0.toByte() }.shouldBeTrue()
    }

    private class FieldFixture {
        @field:KmsEncrypted
        var value: String = ""
    }
}
