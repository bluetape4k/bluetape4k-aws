package io.bluetape4k.aws.spring.sqs

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import tools.jackson.databind.ObjectMapper
import io.bluetape4k.aws.spring.ConditionalOnAwsEnabled

/**
 * Jackson 3가 있으면 Jackson 기반 SQS 메시지 변환기를 등록합니다.
 */
@AutoConfiguration(
    before = [SqsAutoConfiguration::class],
    afterName = ["org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration"],
)
@ConditionalOnAwsEnabled
@ConditionalOnClass(name = ["tools.jackson.databind.ObjectMapper"])
@ConditionalOnProperty(prefix = "bluetape4k.aws.sqs", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class SqsJacksonMessageConverterAutoConfiguration {

    @Bean
    @ConditionalOnBean(ObjectMapper::class)
    @ConditionalOnMissingBean(SqsMessageConverter::class)
    fun sqsMessageConverter(objectMapper: ObjectMapper): SqsMessageConverter =
        JacksonSqsMessageConverter(objectMapper)
}
