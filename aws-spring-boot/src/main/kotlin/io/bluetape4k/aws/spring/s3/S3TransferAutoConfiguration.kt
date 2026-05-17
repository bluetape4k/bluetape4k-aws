package io.bluetape4k.aws.spring.s3

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.transfer.s3.S3TransferManager

/**
 * Auto-configures [S3TransferManager] and [S3TransferTemplate] beans for high-throughput S3 transfers.
 *
 * ## Behavior / Contract
 *
 * - Activates when `software.amazon.awssdk.transfer.s3.S3TransferManager` is on the classpath
 *   and `bluetape4k.aws.s3.enabled=true` (default: true).
 * - Requires an existing [S3AsyncClient] bean, supplied by [S3AutoConfiguration].
 * - [S3TransferManager] bean is only created when neither [S3TransferManager] nor
 *   [S3TransferOperations] is already present in the context.
 * - [S3TransferTemplate] bean is only created when [S3TransferOperations] is absent and
 *   [S3TransferManager] is present.
 * - Both beans can be disabled individually via `bluetape4k.aws.s3.transfer.enabled=false`.
 *
 * ## Usage
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
@ConditionalOnClass(
    name = [
        "software.amazon.awssdk.services.s3.S3AsyncClient",
        "software.amazon.awssdk.transfer.s3.S3TransferManager",
    ]
)
@ConditionalOnProperty(prefix = "bluetape4k.aws.s3", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class S3TransferAutoConfiguration {

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
    ): S3TransferTemplate =
        S3TransferTemplate(transferManager)
}
