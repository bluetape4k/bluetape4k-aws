package io.bluetape4k.aws.spring.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.aws.spring.parameterstore.ParameterStoreConfigDataLoader
import io.bluetape4k.aws.spring.parameterstore.ParameterStoreConfigDataLocationResolver
import io.bluetape4k.aws.spring.s3.S3ConfigDataLoader
import io.bluetape4k.aws.spring.s3.S3ConfigDataLocationResolver
import io.bluetape4k.aws.spring.secretsmanager.SecretsManagerConfigDataLoader
import io.bluetape4k.aws.spring.secretsmanager.SecretsManagerConfigDataLocationResolver
import org.junit.jupiter.api.Test
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext
import org.springframework.boot.context.config.ConfigDataLoader
import org.springframework.boot.context.config.ConfigDataLocationResolver
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.logging.DeferredLogFactory
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.Modifier

class AwsConfigDataSpiAbiTest {

    @Test
    fun `SPI constructors and generic resource contracts remain stable`() {
        AwsConfigDataResource::class.java.declaredConstructors
            .filterNot { it.isSynthetic }
            .single()
            .modifiers.let(Modifier::isPrivate)
            .shouldBeEqualTo(true)

        listOf(
            S3ConfigDataLocationResolver::class.java,
            ParameterStoreConfigDataLocationResolver::class.java,
            SecretsManagerConfigDataLocationResolver::class.java,
        ).forEach { resolver ->
            resolver.constructors.single().parameterTypes.toList() shouldBeEqualTo listOf(
                DeferredLogFactory::class.java,
                Binder::class.java,
                ConfigurableBootstrapContext::class.java,
            )
            resolver.genericInterfaces shouldContainParameterizedResource ConfigDataLocationResolver::class.java
        }

        listOf(
            S3ConfigDataLoader::class.java,
            ParameterStoreConfigDataLoader::class.java,
            SecretsManagerConfigDataLoader::class.java,
        ).forEach { loader ->
            loader.constructors.single().parameterTypes.toList() shouldBeEqualTo listOf(
                DeferredLogFactory::class.java,
                ConfigurableBootstrapContext::class.java,
            )
            loader.genericInterfaces shouldContainParameterizedResource ConfigDataLoader::class.java
        }
    }

    private infix fun Array<Type>.shouldContainParameterizedResource(rawType: Class<*>) {
        val resourceTypes = filterIsInstance<ParameterizedType>()
            .filter { it.rawType == rawType }
            .map { it.actualTypeArguments.single() }

        resourceTypes shouldContain AwsConfigDataResource::class.java
    }
}
