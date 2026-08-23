package io.bluetape4k.aws.spring.config

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.springframework.boot.context.config.ConfigDataLoader
import org.springframework.boot.context.config.ConfigDataLocationResolver
import org.springframework.core.io.support.SpringFactoriesLoader

@Suppress("DEPRECATION")
class AwsConfigDataFactoryRegistrationTest {

    @Test
    fun `spring factories keeps legacy EPP and registers each ConfigData pair once`() {
        val classLoader = javaClass.classLoader
        val resolverNames = SpringFactoriesLoader.loadFactoryNames(ConfigDataLocationResolver::class.java, classLoader)
        val loaderNames = SpringFactoriesLoader.loadFactoryNames(ConfigDataLoader::class.java, classLoader)
        val expectedResolvers = listOf(
            "io.bluetape4k.aws.spring.s3.S3ConfigDataLocationResolver",
            "io.bluetape4k.aws.spring.parameterstore.ParameterStoreConfigDataLocationResolver",
            "io.bluetape4k.aws.spring.secretsmanager.SecretsManagerConfigDataLocationResolver",
            "io.bluetape4k.aws.spring.appconfig.AppConfigDataLocationResolver",
        )
        val expectedLoaders = listOf(
            "io.bluetape4k.aws.spring.s3.S3ConfigDataLoader",
            "io.bluetape4k.aws.spring.parameterstore.ParameterStoreConfigDataLoader",
            "io.bluetape4k.aws.spring.secretsmanager.SecretsManagerConfigDataLoader",
            "io.bluetape4k.aws.spring.appconfig.AppConfigDataLoader",
        )

        resolverNames.count { it in expectedResolvers } shouldBeEqualTo expectedResolvers.size
        loaderNames.count { it in expectedLoaders } shouldBeEqualTo expectedLoaders.size
        resolverNames.filter { it in expectedResolvers }.toSet().size shouldBeEqualTo expectedResolvers.size
        loaderNames.filter { it in expectedLoaders }.toSet().size shouldBeEqualTo expectedLoaders.size

        val environmentPostProcessors = SpringFactoriesLoader.loadFactoryNames(
            org.springframework.boot.EnvironmentPostProcessor::class.java,
            classLoader,
        )
        environmentPostProcessors.filter { it.startsWith("io.bluetape4k.aws.spring") } shouldBeEqualTo listOf(
            "io.bluetape4k.aws.spring.secretsmanager.SecretsManagerEnvironmentPostProcessor",
            "io.bluetape4k.aws.spring.s3.S3ConfigEnvironmentPostProcessor",
            "io.bluetape4k.aws.spring.parameterstore.ParameterStoreEnvironmentPostProcessor",
        )
    }
}
