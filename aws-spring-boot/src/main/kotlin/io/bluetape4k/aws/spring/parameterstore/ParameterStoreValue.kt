package io.bluetape4k.aws.spring.parameterstore

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.annotation.AliasFor

/**
 * Composed Spring [Value] annotation for values loaded from Parameter Store.
 *
 * The [value] contract is the same as Spring `@Value`, so use normal
 * placeholders such as `@ParameterStoreValue("\${app.db.password}")`.
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
