package io.bluetape4k.aws.spring.sns.annotation.endpoint

import org.springframework.aot.hint.BindingReflectionHintsRegistrar
import org.springframework.aot.hint.ExecutableMode
import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.ReflectionHints
import org.springframework.aot.hint.annotation.ReflectiveProcessor
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Method

/** Native image에서 SNS controller method와 binding type을 보존합니다. */
class SnsControllerMappingReflectiveProcessor : ReflectiveProcessor {

    override fun registerReflectionHints(hints: ReflectionHints, element: AnnotatedElement) {
        when (element) {
            is Method -> registerMethodHints(hints, element)
            is Class<*> -> hints.registerType(element, MemberCategory.INTROSPECT_DECLARED_METHODS)
        }
    }

    private fun registerMethodHints(hints: ReflectionHints, method: Method) {
        hints.registerType(
            method.declaringClass,
            MemberCategory.INTROSPECT_DECLARED_METHODS,
            MemberCategory.INVOKE_DECLARED_METHODS,
        )
        hints.registerMethod(method, ExecutableMode.INVOKE)
        BindingReflectionHintsRegistrar().registerReflectionHints(hints, *method.genericParameterTypes)
        BindingReflectionHintsRegistrar().registerReflectionHints(hints, method.genericReturnType)
    }
}
