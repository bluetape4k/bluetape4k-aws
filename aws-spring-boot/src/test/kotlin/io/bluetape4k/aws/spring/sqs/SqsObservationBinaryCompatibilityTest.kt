package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.env.Environment
import java.io.ObjectStreamClass

class SqsObservationBinaryCompatibilityTest {

    @Test
    fun `legacy listener descriptors and serialization identity remain available`() {
        SqsListenerAnnotationBeanPostProcessor::class.java.getDeclaredConstructor(
            Environment::class.java,
            SqsProperties::class.java,
            SqsOperations::class.java,
            SqsMessageListenerContainerRegistry::class.java,
            SqsMessageConverter::class.java,
            List::class.java,
        )
        SqsAutoConfiguration::class.java.getDeclaredMethod(
            "sqsListenerAnnotationBeanPostProcessor",
            Environment::class.java,
            SqsProperties::class.java,
            SqsOperations::class.java,
            SqsMessageListenerContainerRegistry::class.java,
            ObjectProvider::class.java,
            ObjectProvider::class.java,
        )
        SqsMessageListenerContainer::class.java.getDeclaredConstructor(
            SqsListenerEndpoint::class.java,
            SqsOperations::class.java,
            SqsListenerMethodInvoker::class.java,
            List::class.java,
            CoroutineDispatcher::class.java,
        )
        SqsProperties.Listener::class.java.getDeclaredConstructor(
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Integer::class.java,
            Integer::class.java,
            Integer::class.java,
            Integer::class.java,
            Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            SqsProperties.Retry::class.java,
        )
        ObjectStreamClass.lookup(SqsProperties.Listener::class.java).serialVersionUID shouldBeEqualTo
            -3742913463973215849L

        val javap = javap(
            SqsListenerAnnotationBeanPostProcessor::class.java,
            SqsAutoConfiguration::class.java,
            SqsMessageListenerContainer::class.java,
            SqsProperties.Listener::class.java,
        )
        LEGACY_DESCRIPTORS.forEach(javap::shouldContain)
    }

    private fun javap(vararg types: Class<*>): String {
        val javaHome = System.getProperty("java.home")
        val classPath = types.first().protectionDomain.codeSource.location.toURI().path
        val process = ProcessBuilder(
            "$javaHome/bin/javap",
            "-classpath",
            classPath,
            "-s",
            *types.map(Class<*>::getName).toTypedArray(),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor() shouldBeEqualTo 0
        return output
    }

    private companion object {
        val LEGACY_DESCRIPTORS = listOf(
            "(Lorg/springframework/core/env/Environment;Lio/bluetape4k/aws/spring/sqs/SqsProperties;" +
                "Lio/bluetape4k/aws/spring/sqs/SqsOperations;" +
                "Lio/bluetape4k/aws/spring/sqs/SqsMessageListenerContainerRegistry;" +
                "Lio/bluetape4k/aws/spring/sqs/SqsMessageConverter;Ljava/util/List;)V",
            "(Lorg/springframework/core/env/Environment;Lio/bluetape4k/aws/spring/sqs/SqsProperties;" +
                "Lio/bluetape4k/aws/spring/sqs/SqsOperations;" +
                "Lio/bluetape4k/aws/spring/sqs/SqsMessageListenerContainerRegistry;" +
                "Lorg/springframework/beans/factory/ObjectProvider;" +
                "Lorg/springframework/beans/factory/ObjectProvider;)" +
                "Lio/bluetape4k/aws/spring/sqs/SqsListenerAnnotationBeanPostProcessor;",
            "(Lio/bluetape4k/aws/spring/sqs/SqsListenerEndpoint;" +
                "Lio/bluetape4k/aws/spring/sqs/SqsOperations;" +
                "Lio/bluetape4k/aws/spring/sqs/SqsListenerMethodInvoker;" +
                "Ljava/util/List;Lkotlinx/coroutines/CoroutineDispatcher;)V",
            "(ZZIIILjava/lang/Integer;Ljava/lang/Integer;Ljava/lang/Integer;Ljava/lang/Integer;IJ" +
                "Lio/bluetape4k/aws/spring/sqs/SqsProperties\$Retry;)V",
        )
    }
}
