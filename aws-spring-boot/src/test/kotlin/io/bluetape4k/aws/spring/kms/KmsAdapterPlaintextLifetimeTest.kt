package io.bluetape4k.aws.spring.kms

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.KLogging
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import java.util.*

class KmsAdapterPlaintextLifetimeTest {

    companion object: KLogging() {
        private const val REPEAT_SIZE = 5
    }

    private val kms = mockk<KmsOperations>()

    @BeforeEach
    fun beforeEach() {
        clearMocks(kms)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `text adapter clears returned plaintext after decoding`() {
        val plain = Fakers.defaultFaker.lorem().paragraph()
        val plaintext = plain.toByteArray()

        coEvery {
            kms.decrypt(any(), any(), any())
        } returns plaintext

        val encrypted = Base64.getEncoder().encodeToString(byteArrayOf(1))

        KmsTextEncryptor(kms).decrypt(encrypted) shouldBeEqualTo plain

        plaintext.all { it == 0.toByte() }.shouldBeTrue()
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `field codec clears returned plaintext after decoding`() = runSuspendIO {
        val plain = Fakers.defaultFaker.lorem().paragraph()
        val plaintext = plain.toByteArray()

        coEvery {
            kms.decrypt(any(), any(), any())
        } returns plaintext

        val annotation = FieldFixture::class.java.getDeclaredField("value").getAnnotation(KmsEncrypted::class.java)

        KmsEncryptedFieldCodec(kms).decrypt("b4k-kms:v1:AQ", annotation) shouldBeEqualTo plain

        plaintext.all { it == 0.toByte() }.shouldBeTrue()
    }

    private class FieldFixture {
        @field:KmsEncrypted
        var value: String = ""
    }
}
