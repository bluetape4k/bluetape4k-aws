package io.bluetape4k.aws.spring.s3

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.core.io.Resource
import org.springframework.core.io.support.ResourcePatternResolver
import software.amazon.awssdk.services.s3.S3Client
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class S3ResourceAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                S3AutoConfiguration::class.java,
                S3ResourceAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues("bluetape4k.aws.s3.region=us-east-1")

    @Test
    fun `registers exact and pattern resolvers after the S3 client`() {
        contextRunner.run { context ->
            context.getBeansOfType(S3Client::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(S3ProtocolResolver::class.java).size shouldBeEqualTo 1
            context.getBean("s3ResourcePatternResolver", S3ResourcePatternResolver::class.java)
                .shouldNotBeNull()
            context.getResource("s3://config-bucket/config/application.yml")
                .shouldNotBeNull()
        }
    }

    @Test
    fun `S3 switch disables client and resolver together`() {
        contextRunner
            .withPropertyValues("bluetape4k.aws.s3.enabled=false")
            .run { context ->
                context.getBeansOfType(S3Client::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(S3ProtocolResolver::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(S3ResourcePatternResolver::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `backs off when no S3 client is present`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(S3ResourceAutoConfiguration::class.java))
            .run { context ->
                context.getBeansOfType(S3ResourcePatternResolver::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(S3ProtocolResolver::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `backs off when the S3 SDK is absent`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(S3ResourceAutoConfiguration::class.java))
            .withClassLoader(FilteredClassLoader("software.amazon.awssdk.services.s3"))
            .run { context ->
                context.startupFailure shouldBeEqualTo null
                context.getBeansOfType(S3ResourcePatternResolver::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(S3ProtocolResolver::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `custom S3 resolver subtypes back off only matching defaults`() {
        contextRunner
            .withUserConfiguration(CustomS3Resolvers::class.java)
            .run { context ->
                context.getBeansOfType(S3ProtocolResolver::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(S3ResourcePatternResolver::class.java).size shouldBeEqualTo 1
                context.containsBean("s3ResourcePatternResolver") shouldBeEqualTo false
            }
    }

    @Test
    fun `value injection resolves exact S3 resource through the protocol chain`() {
        contextRunner
            .withUserConfiguration(ValueInjectionConfiguration::class.java)
            .run { context ->
                context.getBean("valueInjectedResource", Resource::class.java)
                    .description shouldBeEqualTo "S3 resource [s3://bucket/config/application.yml]"
            }
    }

    @Test
    fun `reserved pattern bean name collision fails clearly`() {
        contextRunner
            .withUserConfiguration(UnrelatedPatternBeanConfiguration::class.java)
            .run { context ->
                context.startupFailure.shouldNotBeNull()
        }
    }

    @Test
    fun `registrar adds one resolver per context under concurrent reentry`() {
        val context = mockk<org.springframework.context.ConfigurableApplicationContext>(relaxed = true)
        val provider = mockk<org.springframework.beans.factory.ObjectProvider<S3ProtocolResolver>>()
        val resolver = mockk<S3ProtocolResolver>()
        val beanFactory = DefaultListableBeanFactory()
        val registrar = S3ProtocolResolverRegistrar(context, provider)
        val start = CountDownLatch(1)
        val complete = CountDownLatch(4)
        val executor = Executors.newFixedThreadPool(4)
        every { provider.getObject() } returns resolver
        try {
            repeat(4) {
                executor.submit {
                    start.await(30, TimeUnit.SECONDS)
                    registrar.postProcessBeanFactory(beanFactory)
                    complete.countDown()
                }
            }
            start.countDown()
            complete.await(120, TimeUnit.SECONDS) shouldBeEqualTo true
            verify(exactly = 1) { context.addProtocolResolver(resolver) }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    @Configuration(proxyBeanMethods = false)
    internal class CustomS3Resolvers {
        @Bean
        fun customProtocolResolver(): CustomProtocolResolver =
            CustomProtocolResolver(mockk())

        @Bean
        fun customPatternResolver(
            applicationContext: ApplicationContext,
            provider: org.springframework.beans.factory.ObjectProvider<S3Client>,
        ): CustomPatternResolver =
            CustomPatternResolver(applicationContext, provider)
    }

    internal class CustomProtocolResolver(
        provider: org.springframework.beans.factory.ObjectProvider<S3Client>,
    ): S3ProtocolResolver(provider)

    internal class CustomPatternResolver(
        applicationContext: ApplicationContext,
        provider: org.springframework.beans.factory.ObjectProvider<S3Client>,
    ): S3ResourcePatternResolver(applicationContext, provider)

    @Configuration(proxyBeanMethods = false)
    internal class ValueInjectionConfiguration {
        @Bean
        fun valueInjectedResource(
            @Value("s3://bucket/config/application.yml") resource: Resource,
        ): Resource = resource
    }

    @Configuration(proxyBeanMethods = false)
    internal class UnrelatedPatternBeanConfiguration {
        @Bean(name = ["s3ResourcePatternResolver"])
        fun unrelatedPatternResolver(): ResourcePatternResolver = mockk()
    }
}
