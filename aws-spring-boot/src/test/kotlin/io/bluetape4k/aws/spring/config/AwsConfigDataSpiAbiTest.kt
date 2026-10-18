package io.bluetape4k.aws.spring.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.aws.spring.appconfig.AppConfigDataLoader
import io.bluetape4k.aws.spring.appconfig.AppConfigDataLocationResolver
import io.bluetape4k.aws.spring.parameterstore.ParameterStoreConfigDataLoader
import io.bluetape4k.aws.spring.parameterstore.ParameterStoreConfigDataLocationResolver
import io.bluetape4k.aws.spring.s3.S3ConfigDataLoader
import io.bluetape4k.aws.spring.s3.S3ConfigDataLocationResolver
import io.bluetape4k.aws.spring.secretsmanager.SecretsManagerConfigDataLoader
import io.bluetape4k.aws.spring.secretsmanager.SecretsManagerConfigDataLocationResolver
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext
import org.springframework.boot.context.config.ConfigDataLoader
import org.springframework.boot.context.config.ConfigDataLocationResolver
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.logging.DeferredLogFactory
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import kotlin.reflect.KClass

class AwsConfigDataSpiAbiTest {

    companion object: KLogging()

    @Test
    fun `SPI constructors and generic resource contracts remain stable`() {
        AwsConfigDataResource::class.java.declaredConstructors
            .filterNot { it.isSynthetic }
            .single()
            .modifiers.let(Modifier::isPrivate)
            .shouldBeTrue()

        listOf(
            S3ConfigDataLocationResolver::class.java,
            ParameterStoreConfigDataLocationResolver::class.java,
            SecretsManagerConfigDataLocationResolver::class.java,
            AppConfigDataLocationResolver::class.java,
        ).forEach { resolver ->
            resolver.constructors.single().parameterTypes.toList() shouldBeEqualTo listOf(
                DeferredLogFactory::class.java,
                Binder::class.java,
                ConfigurableBootstrapContext::class.java,
            )
            resolver.genericInterfaces shouldContainParameterizedResource ConfigDataLocationResolver::class
        }

        listOf(
            S3ConfigDataLoader::class.java,
            ParameterStoreConfigDataLoader::class.java,
            SecretsManagerConfigDataLoader::class.java,
            AppConfigDataLoader::class.java,
        ).forEach { loader ->
            loader.constructors.single().parameterTypes.toList() shouldBeEqualTo listOf(
                DeferredLogFactory::class.java,
                ConfigurableBootstrapContext::class.java,
            )
            loader.genericInterfaces shouldContainParameterizedResource ConfigDataLoader::class
        }
    }

    private infix fun Array<Type>.shouldContainParameterizedResource(rawType: KClass<*>) {
        val resourceTypes = filterIsInstance<ParameterizedType>()
            .filter { it.rawType == rawType.java }
            .map { it.actualTypeArguments.single() }

        resourceTypes shouldContain AwsConfigDataResource::class.java
    }
}
