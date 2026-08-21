package io.bluetape4k.aws.spring.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.aws.spring.AwsClientCustomizationContext
import io.bluetape4k.aws.spring.AwsProperties
import io.bluetape4k.aws.spring.AwsSyncClientCustomizer
import io.bluetape4k.aws.spring.s3.S3ConfigDataSdkAdapter
import io.bluetape4k.aws.spring.s3.S3ConfigProperties
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext

class AwsConfigDataBootstrapCustomizerTest {

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
        val client = S3ConfigDataSdkAdapter.create(configuration)

        services shouldBeEqualTo listOf("s3")
        (client as software.amazon.awssdk.services.s3.S3Client).close()
    }
}
