package io.bluetape4k.aws.spring.kms

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import io.bluetape4k.aws.spring.ConditionalOnAwsEnabled

/**
 * Auto-configuration for explicit field-level KMS encryption helpers.
 */
@AutoConfiguration(after = [KmsAutoConfiguration::class])
@ConditionalOnAwsEnabled
@ConditionalOnBean(KmsOperations::class)
@ConditionalOnProperty(
    prefix = "bluetape4k.aws.kms",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@ConditionalOnProperty(
    prefix = "bluetape4k.aws.kms.field-encryption",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(KmsProperties::class)
class KmsFieldEncryptionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun kmsEncryptedFieldCodec(
        kmsOperations: KmsOperations,
        properties: KmsProperties,
    ): KmsEncryptedFieldCodec =
        KmsEncryptedFieldCodec(
            kmsOperations = kmsOperations,
            keyId = properties.keyId,
            encryptionContext = properties.encryptionContext,
        )
}
