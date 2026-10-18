package io.bluetape4k.aws.spring.support

import org.springframework.core.io.support.SpringFactoriesLoader
import org.springframework.core.io.support.SpringFactoriesLoader.ArgumentResolver
import org.springframework.core.io.support.SpringFactoriesLoader.FailureHandler

inline fun <reified T: Any> SpringFactoriesLoader.load(
    argumentResolver: ArgumentResolver? = null,
    failureHandler: FailureHandler? = null,
): List<T> =
    load(T::class.java, argumentResolver, failureHandler)
