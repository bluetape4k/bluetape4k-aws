package io.bluetape4k.aws.spring.kms

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
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
            context.getBeansOfType(KmsAsyncClient::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(KmsProperties::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(DataKeyCache::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(KmsOperations::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(KmsCoroutinesEncryptor::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(TextEncryptor::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(KmsTextEncryptor::class.java).size shouldBeEqualTo 1
        }
    }

    @Test
    fun `back off when KMS auto configuration disabled`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.kms.enabled=false")
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(KmsAsyncClient::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(KmsOperations::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(TextEncryptor::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `text encryptor auto configuration backs off when KMS disabled with custom operations`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.kms.enabled=false")
            .withBean(KmsOperations::class.java, { NoopKmsOperations })
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(KmsOperations::class.java).size shouldBeEqualTo 1
                context.getBean(KmsOperations::class.java) shouldBeSameInstanceAs NoopKmsOperations
                context.getBeansOfType(TextEncryptor::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(KmsTextEncryptor::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `text encryptor auto configuration registers properties for custom operations`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KmsTextEncryptorAutoConfiguration::class.java))
            .withBean(KmsOperations::class.java, { NoopKmsOperations })
            .withPropertyValues(
                "bluetape4k.aws.kms.key-id=alias/custom",
                "bluetape4k.aws.kms.encryption-context.service=custom-service",
            )
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(KmsProperties::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(TextEncryptor::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(KmsTextEncryptor::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `custom KmsOperations bean backs off encryptor`() {
        contextRunner
            .withBean(KmsOperations::class.java, { NoopKmsOperations })
            .run { context ->
                context.getBeansOfType(KmsOperations::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(KmsCoroutinesEncryptor::class.java).size shouldBeEqualTo 0
                context.getBean(KmsOperations::class.java) shouldBeSameInstanceAs NoopKmsOperations
            }
    }

    @Test
    fun `KMS auto configuration backs off when KMS SDK is absent`() {
        contextRunner
            .withClassLoader(FilteredClassLoader("software.amazon.awssdk.services.kms"))
            .run { context ->
                context.getBeansOfType(KmsAsyncClient::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(KmsOperations::class.java).size shouldBeEqualTo 0
                context.containsBean("kmsTextEncryptor") shouldBeEqualTo false
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
                context.getBeansOfType(TextEncryptor::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(KmsTextEncryptor::class.java).size shouldBeEqualTo 0
                context.getBean(TextEncryptor::class.java) shouldBeSameInstanceAs custom
            }
    }

    @Test
    fun `text encryptor auto configuration backs off when Spring Security Crypto is absent`() {
        contextRunner
            .withClassLoader(FilteredClassLoader("org.springframework.security.crypto.encrypt"))
            .run { context ->
                context.getBeansOfType(KmsOperations::class.java).size shouldBeEqualTo 1
                context.containsBean("kmsTextEncryptor") shouldBeEqualTo false
            }
    }

    @Test
    fun `disabled data key cache registers noop cache`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.kms.data-key-cache.enabled=false")
            .run { context ->
                context.getBeansOfType(DataKeyCache::class.java).size shouldBeEqualTo 1
                context.getBean(DataKeyCache::class.java) shouldBeSameInstanceAs NoopDataKeyCache
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
                context.startupFailure.shouldNotBeNull()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                messages shouldContain "region is required"
            }
    }
}
