package io.bluetape4k.aws.spring.secretsmanager

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.annotation.AliasFor

/**
 * Composed Spring [Value] annotation for values loaded from Secrets Manager.
 *
 * The [value] contract is the same as Spring `@Value`, so use normal
 * placeholders such as `@SecretsValue("\${app.db.password}")`.
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
annotation class SecretsValue(
    @get:AliasFor(annotation = Value::class, attribute = "value")
    val value: String,
)
