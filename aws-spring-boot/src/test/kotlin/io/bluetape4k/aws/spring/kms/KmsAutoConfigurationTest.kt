package io.bluetape4k.aws.spring.kms

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.security.crypto.encrypt.TextEncryptor
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.services.kms.KmsAsyncClient

class KmsAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                KmsAutoConfiguration::class.java,
                KmsTextEncryptorAutoConfiguration::class.java,
            )
        )
        .withBean(AwsCredentialsProvider::class.java, {
            StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))
        })
        .withPropertyValues(
            "bluetape4k.aws.kms.region=us-east-1",
            "bluetape4k.aws.kms.key-id=alias/test",
            "bluetape4k.aws.kms.encryption-context.service=test-service",
        )

    @Test
    fun `register KMS client operations cache and text encryptor`() {
        contextRunner.run { context ->
            assertThat(context).hasSingleBean(KmsAsyncClient::class.java)
            assertThat(context).hasSingleBean(KmsProperties::class.java)
            assertThat(context).hasSingleBean(DataKeyCache::class.java)
            assertThat(context).hasSingleBean(KmsOperations::class.java)
            assertThat(context).hasSingleBean(KmsCoroutinesEncryptor::class.java)
            assertThat(context).hasSingleBean(TextEncryptor::class.java)
            assertThat(context).hasSingleBean(KmsTextEncryptor::class.java)
        }
    }

    @Test
    fun `back off when KMS auto configuration disabled`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.kms.enabled=false")
            .run { context ->
                assertThat(context).doesNotHaveBean(KmsAsyncClient::class.java)
                assertThat(context).doesNotHaveBean(KmsOperations::class.java)
                assertThat(context).doesNotHaveBean(TextEncryptor::class.java)
            }
    }

    @Test
    fun `custom KmsOperations bean backs off encryptor`() {
        contextRunner
            .withBean(KmsOperations::class.java, { NoopKmsOperations })
            .run { context ->
                assertThat(context).hasSingleBean(KmsOperations::class.java)
                assertThat(context).doesNotHaveBean(KmsCoroutinesEncryptor::class.java)
                assertThat(context.getBean(KmsOperations::class.java)).isSameAs(NoopKmsOperations)
            }
    }

    @Test
    fun `KMS auto configuration backs off when KMS SDK is absent`() {
        contextRunner
            .withClassLoader(FilteredClassLoader("software.amazon.awssdk.services.kms"))
            .run { context ->
                assertThat(context).doesNotHaveBean(KmsAsyncClient::class.java)
                assertThat(context).doesNotHaveBean(KmsOperations::class.java)
                assertThat(context).doesNotHaveBean("kmsTextEncryptor")
            }
    }

    @Test
    fun `custom TextEncryptor bean backs off adapter`() {
        val custom = object: TextEncryptor {
            override fun encrypt(text: String): String = text
            override fun decrypt(encryptedText: String): String = encryptedText
        }

        contextRunner
            .withBean(TextEncryptor::class.java, { custom })
            .run { context ->
                assertThat(context).hasSingleBean(TextEncryptor::class.java)
                assertThat(context).doesNotHaveBean(KmsTextEncryptor::class.java)
                assertThat(context.getBean(TextEncryptor::class.java)).isSameAs(custom)
            }
    }

    @Test
    fun `text encryptor auto configuration backs off when Spring Security Crypto is absent`() {
        contextRunner
            .withClassLoader(FilteredClassLoader("org.springframework.security.crypto.encrypt"))
            .run { context ->
                assertThat(context).hasSingleBean(KmsOperations::class.java)
                assertThat(context).doesNotHaveBean("kmsTextEncryptor")
            }
    }

    @Test
    fun `disabled data key cache registers noop cache`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.kms.data-key-cache.enabled=false")
            .run { context ->
                assertThat(context).hasSingleBean(DataKeyCache::class.java)
                assertThat(context.getBean(DataKeyCache::class.java)).isSameAs(NoopDataKeyCache)
            }
    }

    @Test
    fun `endpoint override requires region`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KmsAutoConfiguration::class.java))
            .withBean(AwsCredentialsProvider::class.java, {
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))
            })
            .withPropertyValues("bluetape4k.aws.kms.endpoint-override=http://localhost:4566")
            .run { context ->
                assertThat(context).hasFailed()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                assertThat(messages).contains("region is required")
            }
    }
}
