package io.bluetape4k.aws.spring.s3

import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.AwsClientCustomizationContext
import io.bluetape4k.aws.spring.AwsClientCustomizer
import io.bluetape4k.aws.spring.AwsSyncClientCustomizer
import io.bluetape4k.aws.spring.kms.KmsDataKey
import io.bluetape4k.aws.spring.kms.KmsOperations
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.services.kms.model.DataKeySpec
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3ClientBuilder
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.transfer.s3.S3TransferManager
import software.amazon.awssdk.transfer.s3.model.CompletedDownload
import software.amazon.awssdk.transfer.s3.model.CompletedFileDownload
import software.amazon.awssdk.transfer.s3.model.CompletedFileUpload
import software.amazon.awssdk.transfer.s3.model.CompletedUpload
import software.amazon.awssdk.transfer.s3.model.DownloadFileRequest
import software.amazon.awssdk.transfer.s3.model.DownloadRequest
import software.amazon.awssdk.transfer.s3.model.UploadFileRequest
import software.amazon.awssdk.transfer.s3.model.UploadRequest
import java.nio.file.Path

class S3AutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                S3AutoConfiguration::class.java,
                S3MicrometerAutoConfiguration::class.java,
                S3TransferAutoConfiguration::class.java,
            )
        )
        .withPropertyValues("bluetape4k.aws.s3.region=us-east-1")

    @Test
    fun `register S3 clients and operations`() {
        contextRunner.run { context ->
            context.getBeansOfType(S3Client::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(S3AsyncClient::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(S3Presigner::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(S3Properties::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(S3Operations::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(S3CoroutinesTemplate::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(S3TransferManager::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(S3TransferOperations::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(S3TransferTemplate::class.java).size shouldBeEqualTo 1
        }
    }

    @Test
    fun `register Micrometer S3 operations when registry exists`() {
        contextRunner
            .withBean(SimpleMeterRegistry::class.java, { SimpleMeterRegistry() })
            .run { context ->
                context.getBean(S3Operations::class.java).javaClass shouldBeEqualTo MicrometerS3Operations::class.java
                context.getBeansOfType(S3Operations::class.java).size shouldBeEqualTo 2
                context.getBeansOfType(S3CoroutinesTemplate::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `back off when S3 auto configuration disabled`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.s3.enabled=false")
            .run { context ->
                context.getBeansOfType(S3Client::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(S3AsyncClient::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(S3Presigner::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(S3Operations::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(S3TransferManager::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(S3TransferOperations::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `custom S3Operations bean backs off template`() {
        contextRunner
            .withBean(S3Operations::class.java, { NoopS3Operations })
            .run { context ->
                context.getBeansOfType(S3Operations::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(S3CoroutinesTemplate::class.java).size shouldBeEqualTo 0
                context.getBean(S3Operations::class.java) shouldBeSameInstanceAs NoopS3Operations
            }
    }

    @Test
    fun `client side encryption operations are opt in and require KMS operations`() {
        contextRunner.run { context ->
            context.getBeansOfType(S3ClientSideEncryptionOperations::class.java).size shouldBeEqualTo 0
        }

        contextRunner
            .withPropertyValues("bluetape4k.aws.s3.client-side-encryption.enabled=true")
            .run { context ->
                context.getBeansOfType(S3ClientSideEncryptionOperations::class.java).size shouldBeEqualTo 0
            }

        contextRunner
            .withPropertyValues("bluetape4k.aws.s3.client-side-encryption.enabled=true")
            .withBean(KmsOperations::class.java, { FixedKmsOperations })
            .run { context ->
                context.getBeansOfType(S3ClientSideEncryptionOperations::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(S3ClientSideEncryptionTemplate::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `transfer manager backs off when transfer disabled`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.s3.transfer.enabled=false")
            .run { context ->
                context.getBeansOfType(S3Operations::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(S3TransferManager::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(S3TransferOperations::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `custom S3TransferOperations bean backs off transfer template`() {
        contextRunner
            .withBean(S3TransferOperations::class.java, { NoopS3TransferOperations })
            .run { context ->
                context.getBeansOfType(S3TransferManager::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(S3TransferOperations::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(S3TransferTemplate::class.java).size shouldBeEqualTo 0
                context.getBean(S3TransferOperations::class.java) shouldBeSameInstanceAs NoopS3TransferOperations
            }
    }

    @Test
    fun `custom S3TransferManager bean is adapted to transfer operations`() {
        contextRunner
            .withBean(S3TransferManager::class.java, { mockk(relaxed = true) })
            .run { context ->
                context.getBeansOfType(S3TransferManager::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(S3TransferOperations::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(S3TransferTemplate::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `transfer operations back off when transfer disabled even with custom transfer manager`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.s3.transfer.enabled=false")
            .withBean(S3TransferManager::class.java, { mockk(relaxed = true) })
            .run { context ->
                context.getBeansOfType(S3TransferManager::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(S3TransferOperations::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(S3TransferTemplate::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `basic S3 operations remain available without transfer manager classes`() {
        contextRunner
            .withClassLoader(FilteredClassLoader("software.amazon.awssdk.transfer.s3"))
            .run { context ->
                context.getBeansOfType(S3Operations::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(S3TransferOperations::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `endpoint override requires region`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AwsAutoConfiguration::class.java,
                    S3AutoConfiguration::class.java,
                )
            )
            .withBean(AwsCredentialsProvider::class.java, {
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))
            })
            .withPropertyValues("bluetape4k.aws.s3.endpoint-override=http://localhost:4566")
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                val messages = generateSequence(context.startupFailure) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString("\n")
                messages shouldContain "region is required"
            }
    }

    @Test
    fun `shared defaults provide S3 region and endpoint override`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AwsAutoConfiguration::class.java,
                    S3AutoConfiguration::class.java,
                )
            )
            .withPropertyValues(
                "bluetape4k.aws.region=us-west-2",
                "bluetape4k.aws.s3.endpoint-override=http://localhost:4566",
            )
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(S3Client::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(S3AsyncClient::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `global and S3 sync customizers are applied in order`() {
        S3CustomizerConfig.calls.clear()

        contextRunner
            .withUserConfiguration(S3CustomizerConfig::class.java)
            .run { context ->
                context.getBean(S3Client::class.java).shouldNotBeNull()
                S3CustomizerConfig.calls shouldBeEqualTo listOf("global:s3", "s3")
            }
    }

    @Configuration(proxyBeanMethods = false)
    internal class S3CustomizerConfig {
        @Bean
        fun globalSyncCustomizer(): AwsSyncClientCustomizer =
            RecordingSyncCustomizer("global")

        @Bean
        fun s3ClientCustomizer(): AwsClientCustomizer<S3ClientBuilder> =
            AwsClientCustomizer { calls += "s3" }

        private class RecordingSyncCustomizer(
            private val name: String,
        ): AwsSyncClientCustomizer, Ordered {
            override fun customize(
                context: AwsClientCustomizationContext,
                builder: AwsSyncClientBuilder<*, *>,
            ) {
                calls += "$name:${context.serviceName}"
            }

            override fun getOrder(): Int = 0
        }

        companion object {
            val calls: MutableList<String> = mutableListOf()
        }
    }
}

private object FixedKmsOperations: KmsOperations {
    private val plaintextKey = ByteArray(32) { (it + 1).toByte() }
    private val encryptedKey = "encrypted-data-key".encodeToByteArray()

    override suspend fun encrypt(
        plaintext: ByteArray,
        keyId: String?,
        encryptionContext: Map<String, String>,
    ): ByteArray =
        plaintext.copyOf()

    override suspend fun decrypt(
        ciphertext: ByteArray,
        keyId: String?,
        encryptionContext: Map<String, String>,
    ): ByteArray =
        plaintextKey.copyOf()

    override suspend fun generateDataKey(
        keyId: String?,
        keySpec: DataKeySpec?,
        numberOfBytes: Int?,
        encryptionContext: Map<String, String>,
        useCache: Boolean,
    ): KmsDataKey =
        KmsDataKey(
            keyId = keyId ?: "test-key",
            plaintext = plaintextKey,
            encryptedDataKey = encryptedKey,
        )
}

private object NoopS3TransferOperations: S3TransferOperations {
    override suspend fun upload(
        bucket: String,
        key: String,
        bytes: ByteArray,
        configure: UploadRequest.Builder.() -> Unit,
    ): CompletedUpload =
        throw UnsupportedOperationException("NoopS3TransferOperations does not upload objects.")

    override suspend fun uploadFile(
        bucket: String,
        key: String,
        source: Path,
        configure: UploadFileRequest.Builder.() -> Unit,
    ): CompletedFileUpload =
        throw UnsupportedOperationException("NoopS3TransferOperations does not upload files.")

    override suspend fun downloadBytes(
        bucket: String,
        key: String,
        configure: DownloadRequest.UntypedBuilder.() -> Unit,
    ): CompletedDownload<ResponseBytes<GetObjectResponse>> =
        throw UnsupportedOperationException("NoopS3TransferOperations does not download objects.")

    override suspend fun downloadFile(
        bucket: String,
        key: String,
        destination: Path,
        configure: DownloadFileRequest.Builder.() -> Unit,
    ): CompletedFileDownload =
        throw UnsupportedOperationException("NoopS3TransferOperations does not download files.")
}
