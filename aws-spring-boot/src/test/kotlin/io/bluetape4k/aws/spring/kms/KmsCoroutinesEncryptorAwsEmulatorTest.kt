package io.bluetape4k.aws.spring.kms

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldNotBe
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.test.AwsSpringBootTestEmulator
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.testcontainers.aws.AwsEmulatorServer
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.security.crypto.encrypt.TextEncryptor
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.services.kms.KmsAsyncClient
import software.amazon.awssdk.services.kms.model.DataKeySpec
import java.io.Serializable

class KmsCoroutinesEncryptorAwsEmulatorTest {

    companion object: KLogging() {
        private const val REPEAT_SIZE = 5

        private val awsEmulator: AwsEmulatorServer by lazy {
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
            val kmsAsyncClient = context.getBean<KmsAsyncClient>()
            val operations = context.getBean<KmsOperations>()

            runSuspendIO {
                val keyId = kmsAsyncClient
                    .createKey {
                        it.description("bluetape4k aws-spring-boot kms test")
                    }
                    .await()
                    .keyMetadata()
                    .keyId()

                repeat(REPEAT_SIZE) {
                    val plain = Fakers.defaultFaker.lorem().paragraph()
                    val plaintext = plain.encodeToByteArray()
                    val encryptionContext = mapOf("purpose" to "roundtrip")

                    val ciphertext = operations.encrypt(
                        plaintext = plaintext,
                        keyId = keyId,
                        encryptionContext = encryptionContext,
                    )

                    ciphertext.contentEquals(plaintext).shouldBeFalse()

                    operations.decrypt(
                        ciphertext = ciphertext,
                        keyId = keyId,
                        encryptionContext = encryptionContext,
                    ) shouldBeEqualTo plaintext
                }

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

                try {
                    // cache reads must return independent caller-owned snapshots
                    cachedDataKey shouldNotBe firstDataKey

                    log.debug { "firstDataKey=$firstDataKey" }
                    log.debug { "cacheDataKey=$cachedDataKey" }

                    firstDataKey.plaintext.shouldNotBeEmpty()
                    firstDataKey.encryptedDataKey.shouldNotBeEmpty()
                    cachedDataKey.plaintext.shouldNotBeEmpty()
                    cachedDataKey.encryptedDataKey.shouldNotBeEmpty()
                } finally {
                    firstDataKey.close()
                    cachedDataKey.close()
                }
            }
        }
    }

    @Test
    fun `text encryptor adapter round trips UTF-8 text`() {
        contextRunner().run { context ->
            val kmsAsyncClient = context.getBean<KmsAsyncClient>()
            val operations = context.getBean<KmsOperations>()

            runSuspendIO {
                val keyId = kmsAsyncClient
                    .createKey {
                        it.description("bluetape4k aws-spring-boot text encryptor test")
                    }
                    .await()
                    .keyMetadata()
                    .keyId()

                val textEncryptor: TextEncryptor = KmsTextEncryptor(
                    kmsOperations = operations,
                    keyId = keyId,
                    encryptionContext = mapOf("purpose" to "text"),
                )

                repeat(REPEAT_SIZE) {
                    val text = Fakers.defaultFaker.lorem().paragraph()
                    val ciphertext = textEncryptor.encrypt(text)

                    ciphertext shouldNotBeEqualTo text
                    textEncryptor.decrypt(ciphertext) shouldBeEqualTo text
                }
            }
        }
    }

    @Test
    fun `field encryption codec round trips annotated String`() {
        contextRunner().run { context ->
            val kmsAsyncClient = context.getBean<KmsAsyncClient>()
            val beanCodec = context.getBean<KmsEncryptedFieldCodec>()

            runSuspendIO {
                val keyId = kmsAsyncClient
                    .createKey {
                        it.description("bluetape4k aws-spring-boot field encryption test")
                    }
                    .await()
                    .keyMetadata()
                    .keyId()

                val annotation = FieldEncryptionFixture::class.java
                    .getDeclaredField("secret")
                    .getAnnotation(KmsEncrypted::class.java)

                beanCodec.validate(FieldEncryptionFixture::class.java)

                val keySpecificCodec = KmsEncryptedFieldCodec(
                    kmsOperations = context.getBean<KmsOperations>(),
                    keyId = keyId,
                    encryptionContext = mapOf("service" to "kms-emulator-test"),
                )

                repeat(REPEAT_SIZE) {
                    val fieldValue = Fakers.defaultFaker.lorem().paragraph()
                    val ciphertext = keySpecificCodec.encrypt(fieldValue, annotation)

                    ciphertext shouldNotBeEqualTo fieldValue
                    beanCodec.decrypt(ciphertext, annotation) shouldBeEqualTo fieldValue
                }
            }
        }
    }

    private data class FieldEncryptionFixture(
        @field:KmsEncrypted(encryptionContext = ["purpose=field"])
        val secret: String,
    ): Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }
}
