package io.bluetape4k.aws.spring.support

import org.springframework.boot.bootstrap.BootstrapContext

inline fun <reified T: Any> BootstrapContext.get(): T? = get(T::class.java)

inline fun <reified T: Any> BootstrapContext.getOrElse(other: T?): T? = getOrElse(T::class.java, other)

inline fun <reified T: Any> BootstrapContext.getOrElseSupply(
    crossinline other: () -> T?,
): T? = getOrElseSupply(T::class.java) { other() }

inline fun <reified T: Any, X: Throwable> BootstrapContext.getOrElseThrow(
    crossinline exceptionSupplier: () -> X,
): T? = getOrElseThrow(T::class.java) { exceptionSupplier() }

inline fun <reified T: Any> BootstrapContext.isRegistered(): Boolean = isRegistered(T::class.java)
