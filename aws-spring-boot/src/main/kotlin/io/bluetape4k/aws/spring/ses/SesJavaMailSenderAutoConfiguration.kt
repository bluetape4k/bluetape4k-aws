package io.bluetape4k.aws.spring.ses

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.mail.javamail.JavaMailSender

/**
 * Auto-configuration for the optional Spring [JavaMailSender] SES adapter.
 */
@AutoConfiguration(after = [SesAutoConfiguration::class])
@ConditionalOnClass(
    name = [
        "org.springframework.mail.javamail.JavaMailSender",
        "jakarta.mail.internet.MimeMessage",
        "org.eclipse.angus.mail.util.MailStreamProvider",
    ]
)
@ConditionalOnBean(SesOperations::class)
@ConditionalOnProperty(
    prefix = "bluetape4k.aws.ses.java-mail-sender",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class SesJavaMailSenderAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(JavaMailSender::class)
    fun sesJavaMailSender(sesOperations: SesOperations): JavaMailSender =
        SesJavaMailSender(sesOperations)
}
