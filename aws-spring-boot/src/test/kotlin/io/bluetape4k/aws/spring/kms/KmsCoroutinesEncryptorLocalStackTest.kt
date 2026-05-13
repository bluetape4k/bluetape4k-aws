@file:Suppress("DEPRECATION")

package io.bluetape4k.aws.spring.kms

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.testcontainers.aws.LocalStackServer
import io.bluetape4k.testcontainers.aws.getCredentialProvider
import kotlinx.coroutines.future.await
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.security.crypto.encrypt.TextEncryptor
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.services.kms.KmsAsyncClient
import software.amazon.awssdk.services.kms.model.DataKeySpec

class KmsCoroutinesEncryptorLocalStackTest {

    companion object {
        private val localStack: LocalStackServer = LocalStackServer().withServices("kms")

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            localStack.start()
        }

        @JvmStatic
        @AfterAll
        fun afterAll() {
            localStack.stop()
        }
    }

    private fun contextRunner(): ApplicationContextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                KmsAutoConfiguration::class.java,
                KmsTextEncryptorAutoConfiguration::class.java,
            )
        )
        .withBean(AwsCredentialsProvider::class.java, { localStack.getCredentialProvider() })
        .withPropertyValues(
            "bluetape4k.aws.kms.region=${localStack.regionName}",
            "bluetape4k.aws.kms.endpoint-override=${localStack.awsEndpoint}",
            "bluetape4k.aws.kms.encryption-context.service=kms-localstack-test",
            "bluetape4k.aws.kms.data-key-cache.ttl=PT10M",
            "bluetape4k.aws.kms.data-key-cache.max-size=8",
        )

    @Test
    fun `encrypt decrypt and cache data key through KMS operations`() {
        contextRunner().run { context ->
            val kmsAsyncClient = context.getBean(KmsAsyncClient::class.java)
            val operations = context.getBean(KmsOperations::class.java)

            runTest {
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

                assertThat(ciphertext).isNotEqualTo(plaintext)
                assertThat(
                    operations.decrypt(
                        ciphertext = ciphertext,
                        keyId = keyId,
                        encryptionContext = encryptionContext,
                    )
                ).isEqualTo(plaintext)

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

                assertThat(cachedDataKey).isSameAs(firstDataKey)
                assertThat(firstDataKey.plaintext).isNotEmpty()
                assertThat(firstDataKey.encryptedDataKey).isNotEmpty()
            }
        }
    }

    @Test
    fun `text encryptor adapter round trips UTF-8 text`() {
        contextRunner().run { context ->
            val kmsAsyncClient = context.getBean(KmsAsyncClient::class.java)
            val operations = context.getBean(KmsOperations::class.java)

            runTest {
                val keyId = kmsAsyncClient.createKey {
                    it.description("bluetape4k aws-spring-boot text encryptor test")
                }.await().keyMetadata().keyId()
                val textEncryptor: TextEncryptor = KmsTextEncryptor(
                    kmsOperations = operations,
                    keyId = keyId,
                    encryptionContext = mapOf("purpose" to "text"),
                )

                val ciphertext = textEncryptor.encrypt("short secret value")

                assertThat(ciphertext).isNotEqualTo("short secret value")
                assertThat(textEncryptor.decrypt(ciphertext)).isEqualTo("short secret value")
            }
        }
    }
}
