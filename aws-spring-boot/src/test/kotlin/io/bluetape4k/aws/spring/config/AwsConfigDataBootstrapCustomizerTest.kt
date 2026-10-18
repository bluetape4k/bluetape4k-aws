package io.bluetape4k.aws.spring.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.aws.spring.AwsClientCustomizationContext
import io.bluetape4k.aws.spring.AwsProperties
import io.bluetape4k.aws.spring.AwsSyncClientCustomizer
import io.bluetape4k.aws.spring.s3.S3ConfigDataSdkAdapter
import io.bluetape4k.aws.spring.s3.S3ConfigProperties
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext
import software.amazon.awssdk.services.s3.S3Client

class AwsConfigDataBootstrapCustomizerTest {

    companion object: KLogging()

    @Test
    fun `only an explicitly registered bootstrap customizer changes ConfigData clients`() {
        val bootstrapContext = mockk<ConfigurableBootstrapContext>()
        val services = mutableListOf<String>()
        val customizer = AwsSyncClientCustomizer { context: AwsClientCustomizationContext, _ ->
            services += context.serviceName
        }
        every { bootstrapContext.isRegistered(AwsSyncClientCustomizer::class.java) } returns true
        every { bootstrapContext.get(AwsSyncClientCustomizer::class.java) } returns customizer

        val configuration = AwsConfigDataSupport.ResolverConfiguration(
            aws = AwsProperties(region = "ap-northeast-2"),
            backend = S3ConfigProperties(),
            bootstrapContext = bootstrapContext,
        )
        log.debug { "configuration=$configuration" }

        val client = S3ConfigDataSdkAdapter.create(configuration)
        log.debug { "client=$client" }

        services shouldBeEqualTo listOf("s3")
        client.shouldBeInstanceOf<S3Client>().close()
    }
}
