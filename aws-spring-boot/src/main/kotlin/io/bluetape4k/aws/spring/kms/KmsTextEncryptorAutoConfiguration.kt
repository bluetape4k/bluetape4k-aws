package io.bluetape4k.aws.spring.kms

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.security.crypto.encrypt.TextEncryptor
import io.bluetape4k.aws.spring.ConditionalOnAwsEnabled

/**
 * Optional Spring Security Crypto adapter auto-configuration for AWS KMS.
 */
@AutoConfiguration(after = [KmsAutoConfiguration::class])
@ConditionalOnAwsEnabled
@ConditionalOnClass(name = ["org.springframework.security.crypto.encrypt.TextEncryptor"])
@ConditionalOnBean(KmsOperations::class)
@ConditionalOnProperty(
    prefix = "bluetape4k.aws.kms",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@ConditionalOnProperty(
    prefix = "bluetape4k.aws.kms.text-encryptor",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(KmsProperties::class)
class KmsTextEncryptorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(TextEncryptor::class)
    fun kmsTextEncryptor(
        kmsOperations: KmsOperations,
        properties: KmsProperties,
    ): KmsTextEncryptor =
        KmsTextEncryptor(
            kmsOperations = kmsOperations,
            keyId = properties.keyId,
            encryptionContext = properties.encryptionContext,
        )
}
