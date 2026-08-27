package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.micrometer.context.ContextRegistry
import org.junit.jupiter.api.Test

class SqsObservationDependencyContractTest {

    @Test
    fun `runtime classpath contains context propagation version 1_2_1`() {
        val contextPropagationLocation = ContextRegistry::class.java.protectionDomain.codeSource.location
            .toExternalForm()
        val implementationVersion = ContextRegistry::class.java.`package`.implementationVersion

        (
            implementationVersion == "1.2.1" ||
                contextPropagationLocation.contains("context-propagation-1.2.1")
            ).shouldBeTrue()
    }

    @Test
    fun `public observation signatures do not expose context propagation types`() {
        val forbiddenType = "io.micrometer.context."
        val publicTypes = listOf(
            SqsObservationProperties::class.java,
            SqsObservationMetadata::class.java,
            SqsObservationContext::class.java,
            SqsObservationStage::class.java,
            SqsObservationOutcome::class.java,
            SqsObservationDelivery::class.java,
        ).joinToString("\n") { type ->
            buildString {
                append(type.name)
                type.declaredConstructors.forEach { append(it.toGenericString()) }
                type.declaredMethods.filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
                    .forEach { append(it.toGenericString()) }
                type.declaredFields.filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
                    .forEach { append(it.toGenericString()) }
            }
        }

        publicTypes.contains(forbiddenType).shouldBeEqualTo(false)
    }
}
