package io.bluetape4k.aws.spring.sqs

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import tools.jackson.databind.ObjectMapper

/**
 * Registers the Jackson-backed SQS message converter when Jackson 3 is present.
 */
@AutoConfiguration(after = [SqsAutoConfiguration::class])
@ConditionalOnClass(name = ["tools.jackson.databind.ObjectMapper"])
@ConditionalOnProperty(prefix = "bluetape4k.aws.sqs", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class SqsJacksonMessageConverterAutoConfiguration {

    @Bean
    @ConditionalOnBean(ObjectMapper::class)
    @ConditionalOnMissingBean(SqsMessageConverter::class)
    fun sqsMessageConverter(objectMapper: ObjectMapper): SqsMessageConverter =
        JacksonSqsMessageConverter(objectMapper)
}
