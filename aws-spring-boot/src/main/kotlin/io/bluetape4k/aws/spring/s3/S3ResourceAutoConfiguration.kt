package io.bluetape4k.aws.spring.s3

import io.bluetape4k.aws.spring.ConditionalOnAwsEnabled
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import software.amazon.awssdk.services.s3.S3Client

/**
 * 기존 S3 client 뒤에 exact protocol과 단일 bucket pattern resolver를 등록한다.
 *
 * 이 설정은 기존 S3 backend switch와 client 조건을 그대로 따르며, resolver가
 * client를 새로 만들거나 소유하지 않는다. pattern 기본 bean의 이름은
 * `s3ResourcePatternResolver`로 고정된다.
 */
@AutoConfiguration(after = [S3AutoConfiguration::class])
@ConditionalOnAwsEnabled
@ConditionalOnClass(
    name = [
        "org.springframework.core.io.Resource",
        "org.springframework.core.io.ProtocolResolver",
        "org.springframework.core.io.support.ResourcePatternResolver",
        "software.amazon.awssdk.services.s3.S3Client",
    ],
)
@ConditionalOnProperty(
    prefix = "bluetape4k.aws.s3",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@ConditionalOnBean(S3Client::class)
class S3ResourceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(S3ProtocolResolver::class)
    fun s3ProtocolResolver(
        s3ClientProvider: ObjectProvider<S3Client>,
    ): S3ProtocolResolver =
        S3ProtocolResolver(s3ClientProvider)

    @Bean(name = ["s3ResourcePatternResolver"])
    @ConditionalOnMissingBean(S3ResourcePatternResolver::class)
    fun s3ResourcePatternResolver(
        applicationContext: ApplicationContext,
        s3ClientProvider: ObjectProvider<S3Client>,
    ): S3ResourcePatternResolver =
        S3ResourcePatternResolver(applicationContext, s3ClientProvider)

    companion object {
        /**
         * configuration 인스턴스를 조기에 만들지 않고 resolver 등록만 수행하는 static
         * BeanFactoryPostProcessor를 제공한다.
         */
        @Bean
        @JvmStatic
        fun s3ProtocolResolverRegistrar(
            applicationContext: ConfigurableApplicationContext,
            resolverProvider: ObjectProvider<S3ProtocolResolver>,
        ): BeanFactoryPostProcessor =
            S3ProtocolResolverRegistrar(applicationContext, resolverProvider)
    }
}

/**
 * 하나의 [ConfigurableApplicationContext]에 protocol resolver를 한 번만 등록한다.
 * guard는 instance가 아니라 bean factory singleton으로 보관해 재진입과 동시 호출을
 * 같은 context 범위에서 직렬화한다.
 */
internal class S3ProtocolResolverRegistrar(
    private val applicationContext: ConfigurableApplicationContext,
    private val resolverProvider: ObjectProvider<S3ProtocolResolver>,
): BeanFactoryPostProcessor {

    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) {
        synchronized(beanFactory) {
            if (beanFactory.containsSingleton(REGISTRATION_GUARD)) {
                return
            }
            val resolver = resolverProvider.getObject()
            beanFactory.registerSingleton(REGISTRATION_GUARD, Any())
            applicationContext.addProtocolResolver(resolver)
        }
    }

    private companion object {
        const val REGISTRATION_GUARD = "bluetape4k.s3ProtocolResolver.registered"
    }
}
