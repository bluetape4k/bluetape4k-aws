package io.bluetape4k.aws.spring.sns

import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.core.MethodParameter
import org.springframework.web.method.HandlerMethod

/** SNS handler가 지원하지 않는 parameter를 선언하면 애플리케이션 시작을 실패시킵니다. */
internal class SnsHttpEndpointHandlerMethodValidator(
    private val support: SnsHttpMessageResolverSupport,
    private val handlerMappingClassName: String,
) : BeanPostProcessor {

    @Suppress("ReturnCount")
    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
        if (!Class.forName(handlerMappingClassName, false, bean.javaClass.classLoader).isInstance(bean)) return bean
        val getHandlerMethods = bean.javaClass.methods.firstOrNull { method ->
            method.name == "getHandlerMethods" && method.parameterCount == 0
        } ?: return bean
        @Suppress("UNCHECKED_CAST")
        val handlerMethods = getHandlerMethods.invoke(bean) as? Map<Any, HandlerMethod> ?: return bean
        handlerMethods.values.forEach { handlerMethod ->
            support.validateHandlerMethod(handlerMethod.method)
        }
        return bean
    }
}
