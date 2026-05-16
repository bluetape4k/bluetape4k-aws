package io.bluetape4k.aws.spring.s3

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.transfer.s3.S3TransferManager

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
