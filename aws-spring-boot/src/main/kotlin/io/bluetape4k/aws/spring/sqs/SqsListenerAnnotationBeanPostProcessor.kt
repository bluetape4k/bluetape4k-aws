package io.bluetape4k.aws.spring.sqs

import org.springframework.aop.support.AopUtils
import org.springframework.beans.BeansException
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.core.env.Environment
import org.springframework.util.ReflectionUtils
import java.lang.reflect.Method

/**
 * `@SqsListener` 메서드를 찾아 리스너 컨테이너로 등록하는 BeanPostProcessor.
 */
class SqsListenerAnnotationBeanPostProcessor(
    private val environment: Environment,
    private val properties: SqsProperties,
    private val operations: SqsOperations,
    private val registry: SqsMessageListenerContainerRegistry,
    private val messageConverter: SqsMessageConverter,
    private val interceptors: List<SqsListenerInterceptor>,
): BeanPostProcessor {

    @Throws(BeansException::class)
    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
        if (!properties.listener.enabled) {
            return bean
        }

        val targetClass = AopUtils.getTargetClass(bean)
        ReflectionUtils.doWithMethods(targetClass) { method ->
            val listener = AnnotatedElementUtils.findMergedAnnotation(method, SqsListener::class.java)
            if (listener != null) {
                registerListener(bean, beanName, method, listener)
            }
        }
        return bean
    }

    private fun registerListener(bean: Any, beanName: String, method: Method, listener: SqsListener) {
        val queue = resolveValue(listener.queue, "queue")
        val id = listener.id.takeIf { it.isNotBlank() }
            ?.let { resolveValue(it, "id") }
            ?: "$beanName.${method.name}.$queue"

        val effective = properties.listener
        val endpoint = SqsListenerEndpoint(
            id = id,
            queue = properties.queues[queue]?.url ?: queue,
            maxMessages = listener.maxMessages.takeIf { it > 0 } ?: effective.maxMessages,
            waitTimeSeconds = listener.waitTimeSeconds.takeIf { it >= 0 } ?: effective.waitTimeSeconds,
            visibilityTimeoutSeconds = listener.visibilityTimeoutSeconds.takeIf { it >= 0 }
                ?: effective.visibilityTimeoutSeconds,
            errorVisibilityTimeoutSeconds = listener.errorVisibilityTimeoutSeconds.takeIf { it >= 0 }
                ?: effective.errorVisibilityTimeoutSeconds,
            autoStartup = listener.autoStartup && effective.autoStartup,
            phase = effective.phase,
            concurrency = effective.concurrency,
            stopTimeoutMillis = effective.stopTimeoutMillis,
            retry = effective.retry,
        )
        val invoker = SqsListenerMethodInvoker(bean, AopUtils.selectInvocableMethod(method, bean.javaClass), messageConverter)
        registry.register(id, SqsMessageListenerContainer(endpoint, operations, invoker, interceptors))
    }

    private fun resolveValue(value: String, name: String): String {
        require(!value.contains("#{")) { "SpEL is not supported for @SqsListener $name: $value" }
        return environment.resolveRequiredPlaceholders(value)
    }
}
