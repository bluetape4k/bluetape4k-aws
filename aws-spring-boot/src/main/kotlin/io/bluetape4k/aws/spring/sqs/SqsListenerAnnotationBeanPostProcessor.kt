package io.bluetape4k.aws.spring.sqs

import org.springframework.aop.support.AopUtils
import org.springframework.beans.BeansException
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.core.env.Environment
import org.springframework.util.ReflectionUtils
import java.lang.reflect.Method
import java.lang.reflect.Modifier

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
            if (method.isSynthetic || method.isBridge || Modifier.isStatic(method.modifiers)) {
                return@doWithMethods
            }
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
        val maxMessages = listener.maxMessages.takeIf { it != -1 } ?: effective.maxMessages
        require(maxMessages in 1..MAX_SQS_BATCH_SIZE) {
            "maxMessages must be between 1 and 10."
        }
        val invoker = SqsListenerMethodInvoker(
            bean,
            AopUtils.selectInvocableMethod(method, bean.javaClass),
            messageConverter,
        )
        val endpoint = SqsListenerEndpoint(
            id = id,
            queue = properties.queues[queue]?.url ?: queue,
            maxMessages = maxMessages,
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
            batch = listener.batch,
            acknowledgementMode = resolveAcknowledgementMode(listener, invoker),
        )
        registry.register(id, SqsMessageListenerContainer(endpoint, operations, invoker, interceptors))
    }

    private fun resolveAcknowledgementMode(
        listener: SqsListener,
        invoker: SqsListenerMethodInvoker,
    ): SqsAcknowledgementMode {
        if (listener.batch) {
            require(invoker.hasListPayload) { "batch=true requires a List payload" }
            invoker.validateBatchSignature()
        } else {
            require(!invoker.hasListPayload) { "batch=false does not accept List payload" }
            require(!invoker.hasBatchAcknowledgement) {
                "SqsBatchAcknowledgement requires batch=true"
            }
        }

        require(!(invoker.hasSingleAcknowledgement && invoker.hasBatchAcknowledgement)) {
            "@SqsListener method supports only one acknowledgement parameter"
        }

        val hasAcknowledgement = invoker.hasSingleAcknowledgement || invoker.hasBatchAcknowledgement
        val resolved = when (listener.acknowledgementMode) {
            SqsAcknowledgementMode.INHERIT ->
                if (hasAcknowledgement) SqsAcknowledgementMode.MANUAL else SqsAcknowledgementMode.ON_SUCCESS
            else -> listener.acknowledgementMode
        }

        when (resolved) {
            SqsAcknowledgementMode.ON_SUCCESS -> {
                require(!hasAcknowledgement) {
                    "ON_SUCCESS cannot declare SqsAcknowledgement"
                }
            }
            SqsAcknowledgementMode.MANUAL -> {
                if (listener.batch) {
                    require(invoker.hasBatchAcknowledgement) {
                        "MANUAL requires SqsBatchAcknowledgement"
                    }
                } else {
                    require(invoker.hasSingleAcknowledgement) {
                        "MANUAL requires SqsAcknowledgement"
                    }
                }
            }
            SqsAcknowledgementMode.INHERIT -> error("INHERIT must be resolved")
        }
        return resolved
    }

    private fun resolveValue(value: String, name: String): String {
        require(!value.contains("#{")) { "SpEL is not supported for @SqsListener $name: $value" }
        return environment.resolveRequiredPlaceholders(value)
    }
}
