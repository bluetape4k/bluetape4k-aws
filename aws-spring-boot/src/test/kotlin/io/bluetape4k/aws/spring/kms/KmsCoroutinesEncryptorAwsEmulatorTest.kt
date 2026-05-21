package io.bluetape4k.aws.spring.kms

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.aws.spring.test.AwsSpringBootTestEmulator
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.security.crypto.encrypt.TextEncryptor
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.services.kms.KmsAsyncClient
import software.amazon.awssdk.services.kms.model.DataKeySpec

class KmsCoroutinesEncryptorAwsEmulatorTest {

    companion object {
        private val awsEmulator by lazy {
            AwsSpringBootTestEmulator.get("kms")
        }
    }

    private fun contextRunner(): ApplicationContextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                KmsAutoConfiguration::class.java,
                KmsFieldEncryptionAutoConfiguration::class.java,
                KmsTextEncryptorAutoConfiguration::class.java,
            )
        )
        .withBean(AwsCredentialsProvider::class.java, { awsEmulator.getCredentialProvider() })
        .withPropertyValues(
            "bluetape4k.aws.kms.region=${awsEmulator.regionName}",
            "bluetape4k.aws.kms.endpoint-override=${awsEmulator.awsEndpoint}",
            "bluetape4k.aws.kms.encryption-context.service=kms-emulator-test",
            "bluetape4k.aws.kms.data-key-cache.ttl=PT10M",
            "bluetape4k.aws.kms.data-key-cache.max-size=8",
        )

    @Test
    fun `encrypt decrypt and cache data key through KMS operations`() {
        contextRunner().run { context ->
            val kmsAsyncClient = context.getBean(KmsAsyncClient::class.java)
            val operations = context.getBean(KmsOperations::class.java)

            runSuspendIO {
                val keyId = kmsAsyncClient.createKey {
                    it.description("bluetape4k aws-spring-boot kms test")
                }.await().keyMetadata().keyId()

                val plaintext = "hello kms".encodeToByteArray()
                val encryptionContext = mapOf("purpose" to "roundtrip")

                val ciphertext = operations.encrypt(
                    plaintext = plaintext,
                    keyId = keyId,
                    encryptionContext = encryptionContext,
                )

                ciphertext.contentEquals(plaintext) shouldBeEqualTo false
                operations.decrypt(
                    ciphertext = ciphertext,
                    keyId = keyId,
                    encryptionContext = encryptionContext,
                ) shouldBeEqualTo plaintext

                val firstDataKey = operations.generateDataKey(
                    keyId = keyId,
                    keySpec = DataKeySpec.AES_256,
                    encryptionContext = mapOf("purpose" to "cache"),
                )
                val cachedDataKey = operations.generateDataKey(
                    keyId = keyId,
                    keySpec = DataKeySpec.AES_256,
                    encryptionContext = mapOf("purpose" to "cache"),
                )

                cachedDataKey shouldBeSameInstanceAs firstDataKey
                firstDataKey.plaintext.shouldNotBeEmpty()
                firstDataKey.encryptedDataKey.shouldNotBeEmpty()
            }
        }
    }

    @Test
    fun `text encryptor adapter round trips UTF-8 text`() {
        contextRunner().run { context ->
            val kmsAsyncClient = context.getBean(KmsAsyncClient::class.java)
            val operations = context.getBean(KmsOperations::class.java)

            runSuspendIO {
                val keyId = kmsAsyncClient.createKey {
                    it.description("bluetape4k aws-spring-boot text encryptor test")
                }.await().keyMetadata().keyId()
                val textEncryptor: TextEncryptor = KmsTextEncryptor(
                    kmsOperations = operations,
                    keyId = keyId,
                    encryptionContext = mapOf("purpose" to "text"),
                )

                val ciphertext = textEncryptor.encrypt("short secret value")

                ciphertext shouldNotBeEqualTo "short secret value"
                textEncryptor.decrypt(ciphertext) shouldBeEqualTo "short secret value"
            }
        }
    }

    @Test
    fun `field encryption codec round trips annotated String`() {
        contextRunner().run { context ->
            val kmsAsyncClient = context.getBean(KmsAsyncClient::class.java)
            val beanCodec = context.getBean(KmsEncryptedFieldCodec::class.java)

            runSuspendIO {
                val keyId = kmsAsyncClient.createKey {
                    it.description("bluetape4k aws-spring-boot field encryption test")
                }.await().keyMetadata().keyId()
                val annotation = FieldEncryptionFixture::class.java
                    .getDeclaredField("secret")
                    .getAnnotation(KmsEncrypted::class.java)
                beanCodec.validate(FieldEncryptionFixture::class.java)
                val keySpecificCodec = KmsEncryptedFieldCodec(
                    kmsOperations = context.getBean(KmsOperations::class.java),
                    keyId = keyId,
                    encryptionContext = mapOf("service" to "kms-emulator-test"),
                )

                val ciphertext = keySpecificCodec.encrypt("field secret value", annotation)

                ciphertext shouldNotBeEqualTo "field secret value"
                beanCodec.decrypt(ciphertext, annotation) shouldBeEqualTo "field secret value"
            }
        }
    }

    private data class FieldEncryptionFixture(
        @field:KmsEncrypted(encryptionContext = ["purpose=field"])
        val secret: String,
    )
}
