package io.bluetape4k.aws.spring.parameterstore

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.annotation.AliasFor

/**
 * Parameter Store에서 로드한 값에 사용하는 합성 Spring [Value] 애너테이션입니다.
 *
 * [value] 계약은 Spring `@Value`와 같으므로 `@ParameterStoreValue("\${app.db.password}")`와
 * 같은 일반 플레이스홀더를 사용하세요.
 */
@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Value("")
annotation class ParameterStoreValue(
    @get:AliasFor(annotation = Value::class, attribute = "value")
    val value: String,
)
