package io.bluetape4k.aws.spring.config

import io.bluetape4k.aws.spring.AwsClientCustomizationContext
import io.bluetape4k.aws.spring.AwsSyncClientCustomizer
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder

/** 명시적으로 bootstrap에 등록한 전역 sync client customizer만 적용합니다. */
internal fun <B, C> B.applyConfigDataBootstrapCustomizer(
    bootstrapContext: ConfigurableBootstrapContext,
    serviceName: String,
): B where B : AwsSyncClientBuilder<B, C> = apply {
    if (bootstrapContext.isRegistered(AwsSyncClientCustomizer::class.java)) {
        checkNotNull(bootstrapContext.get(AwsSyncClientCustomizer::class.java))
            .customize(AwsClientCustomizationContext(serviceName), this)
    }
}
