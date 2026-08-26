package io.bluetape4k.aws.spring.sns

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.web.method.HandlerMethod

/** SNS handler가 지원하지 않는 parameter를 선언하면 애플리케이션 시작을 실패시킵니다. */
internal class SnsHttpEndpointHandlerMethodValidator(
    private val support: SnsHttpMessageResolverSupport,
    private val handlerMethods: () -> Map<*, HandlerMethod>,
) : SmartInitializingSingleton {

    override fun afterSingletonsInstantiated() {
        handlerMethods().values.forEach { handlerMethod ->
            support.validateHandlerMethod(handlerMethod.method)
        }
    }
}
