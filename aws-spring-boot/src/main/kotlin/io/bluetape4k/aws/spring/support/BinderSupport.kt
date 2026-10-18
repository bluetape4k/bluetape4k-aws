package io.bluetape4k.aws.spring.support

import org.springframework.boot.context.properties.bind.BindResult
import org.springframework.boot.context.properties.bind.Binder

inline fun <reified T: Any> Binder.bind(name: String): BindResult<T> =
    bind(name, T::class.java)

inline fun <reified T: Any> Binder.bindOrCreate(name: String): T =
    bindOrCreate(name, T::class.java) 
