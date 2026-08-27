package io.bluetape4k.aws.spring.s3

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldContain
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import javax.crypto.KeyGenerator

class S3ClientSideEncryptionProviderTest {

    @Test
    fun `defaults to KMS and accepts key version`() {
        val defaults = S3Properties.ClientSideEncryption()

        defaults.provider shouldBeEqualTo ClientSideEncryptionProvider.KMS
        defaults.keyVersion shouldBeEqualTo null

        val configured = S3Properties.ClientSideEncryption(keyVersion = "v2")

        configured.keyVersion shouldBeEqualTo "v2"
    }

    @Test
    fun `provider factories return caller key material`() {
        val secretKey = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        val aesProvider = S3AesProvider.of(secretKey)
        val rsaProvider = S3RsaProvider.of(keyPair)

        aesProvider.generateSecretKey() shouldBeSameInstanceAs secretKey
        rsaProvider.generateKeyPair() shouldBeSameInstanceAs keyPair
    }

    @Test
    fun `key id and version reject blank or control character`() {
        val blankKeyId = assertFailsWith<IllegalArgumentException> {
            S3Properties.ClientSideEncryption(keyId = " ")
        }
        blankKeyId.message.orEmpty() shouldContain "keyId"

        val controlKeyId = assertFailsWith<IllegalArgumentException> {
            S3Properties.ClientSideEncryption(keyId = "alias/test\u000B")
        }
        controlKeyId.message.orEmpty() shouldContain "keyId"

        val blankKeyVersion = assertFailsWith<IllegalArgumentException> {
            S3Properties.ClientSideEncryption(keyVersion = "")
        }
        blankKeyVersion.message.orEmpty() shouldContain "keyVersion"

        val controlKeyVersion = assertFailsWith<IllegalArgumentException> {
            S3Properties.ClientSideEncryption(keyVersion = "v1\u000B")
        }
        controlKeyVersion.message.orEmpty() shouldContain "keyVersion"
    }
}
