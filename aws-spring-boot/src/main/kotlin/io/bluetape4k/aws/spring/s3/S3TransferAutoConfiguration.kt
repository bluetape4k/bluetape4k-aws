package io.bluetape4k.aws.spring.s3

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import tools.jackson.databind.ObjectMapper
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.transfer.s3.S3TransferManager
import io.bluetape4k.aws.spring.ConditionalOnAwsEnabled

/**
 * 고처리량 S3 전송을 위한 [S3TransferManager]와 [S3TransferTemplate] Bean을 자동 구성합니다.
 *
 * ## 동작/계약
 *
 * - 클래스패스에 `software.amazon.awssdk.transfer.s3.S3TransferManager`가 있고
 *   `bluetape4k.aws.s3.enabled=true`(기본값: true)이면 활성화됩니다.
 * - [S3AutoConfiguration]이 제공하는 기존 [S3AsyncClient] Bean이 필요합니다.
 * - 컨텍스트에 [S3TransferManager]와 [S3TransferOperations]가 모두 없을 때만
 *   [S3TransferManager] Bean을 생성합니다.
 * - [S3TransferOperations]가 없고 [S3TransferManager]가 있을 때만 [S3TransferTemplate] Bean을 생성합니다.
 * - `bluetape4k.aws.s3.transfer.enabled=false`로 두 Bean을 함께 비활성화할 수 있습니다.
 *
 * ## 사용법
 *
 * ```kotlin
 * @Service
 * class FileUploadService(private val transferTemplate: S3TransferTemplate) {
 *     suspend fun upload(bucket: String, key: String, file: Path): CompletedUpload =
 *         transferTemplate.upload(bucket, key, file)
 * }
 * ```
 */
@AutoConfiguration(after = [S3AutoConfiguration::class])
@ConditionalOnAwsEnabled
@ConditionalOnClass(
    name = [
        "software.amazon.awssdk.services.s3.S3AsyncClient",
        "software.amazon.awssdk.transfer.s3.S3TransferManager",
    ]
)
@ConditionalOnProperty(prefix = "bluetape4k.aws.s3", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(S3Properties::class)
class S3TransferAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(S3ObjectContentTypeResolver::class)
    fun s3ObjectContentTypeResolver(): S3ObjectContentTypeResolver =
        DefaultS3ObjectContentTypeResolver()

    @Bean
    @ConditionalOnClass(name = ["tools.jackson.databind.ObjectMapper"])
    @ConditionalOnBean(ObjectMapper::class)
    @ConditionalOnMissingBean(S3ObjectConverter::class)
    fun s3ObjectConverter(objectMapper: ObjectMapper): S3ObjectConverter<Any> =
        JacksonS3ObjectConverter(objectMapper)

    @Bean(destroyMethod = "close")
    @ConditionalOnBean(S3AsyncClient::class)
    @ConditionalOnMissingBean(value = [S3TransferManager::class, S3TransferOperations::class])
    @ConditionalOnProperty(
        prefix = "bluetape4k.aws.s3.transfer",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun s3TransferManager(
        s3AsyncClient: S3AsyncClient,
        properties: S3Properties,
    ): S3TransferManager =
        S3TransferManager.builder()
            .s3Client(s3AsyncClient)
            .apply {
                properties.transfer.uploadDirectoryMaxDepth?.let { uploadDirectoryMaxDepth(it) }
                properties.transfer.transferDirectoryMaxConcurrency?.let { transferDirectoryMaxConcurrency(it) }
            }
            .build()

    @Bean
    @ConditionalOnMissingBean(S3TransferOperations::class)
    @ConditionalOnBean(S3TransferManager::class)
    @ConditionalOnProperty(
        prefix = "bluetape4k.aws.s3.transfer",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun s3TransferOperations(
        transferManager: S3TransferManager,
        properties: S3Properties,
        contentTypeResolver: S3ObjectContentTypeResolver,
    ): S3TransferTemplate =
        S3TransferTemplate(transferManager, properties, contentTypeResolver)

    @Bean
    @ConditionalOnBean(
        value = [
            S3ClientSideEncryptionProviderTemplate::class,
            S3TransferOperations::class,
            S3OutputStreamProvider::class,
        ],
    )
    @ConditionalOnMissingBean(S3ClientSideEncryptionTransferOperations::class)
    @ConditionalOnProperty(
        prefix = "bluetape4k.aws.s3.client-side-encryption",
        name = ["enabled"],
        havingValue = "true",
    )
    @ConditionalOnProperty(
        prefix = "bluetape4k.aws.s3.transfer",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun s3ClientSideEncryptionTransferOperations(
        s3AsyncClient: S3AsyncClient,
        providerTemplate: S3ClientSideEncryptionProviderTemplate,
        transferOperations: S3TransferOperations,
        outputStreamProvider: S3OutputStreamProvider,
    ): S3ClientSideEncryptionTransferOperations =
        S3ClientSideEncryptionTransferTemplate(
            s3AsyncClient,
            providerTemplate,
            transferOperations,
            outputStreamProvider,
        )
}
