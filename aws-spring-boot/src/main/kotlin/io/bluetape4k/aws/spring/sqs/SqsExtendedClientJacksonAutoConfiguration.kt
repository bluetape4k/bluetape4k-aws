package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.aws.spring.ConditionalOnAwsEnabled
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import tools.jackson.databind.ObjectMapper

/** 지원되는 Jackson 3 classpath에서 Extended Client safe DTO module을 등록합니다. */
@AutoConfiguration(
    afterName = [
        "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration",
        "io.bluetape4k.aws.spring.sqs.SqsExtendedClientAutoConfiguration",
    ],
)
@ConditionalOnAwsEnabled
@ConditionalOnClass(name = ["tools.jackson.databind.ObjectMapper"])
@ConditionalOnBean(ObjectMapper::class)
class SqsExtendedClientJacksonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SqsExtendedClientJacksonModule::class)
    fun sqsExtendedClientJacksonModule(): SqsExtendedClientJacksonModule = SqsExtendedClientJacksonModule()
}
