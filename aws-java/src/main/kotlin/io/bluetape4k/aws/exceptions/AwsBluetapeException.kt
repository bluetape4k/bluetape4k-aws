package io.bluetape4k.aws.exceptions

import io.bluetape4k.exceptions.BluetapeException

/**
 * Common module-level base exception class for AWS-related exceptions.
 *
 * ## Behavior and contract
 * - Extends [BluetapeException] and exposes message/cause constructor overloads as-is.
 * - Callers can choose the default, message-only, message-plus-cause, or cause-only constructor.
 *
 * ```kotlin
 * val ex = AwsBluetapeException("aws failure")
 * // ex.message == "aws failure"
 * ```
 */
open class AwsBluetapeException: BluetapeException {
    /**
     * Creates an exception instance without message or cause.
     *
     * ## Behavior and contract
     * - Calls the superclass default constructor.
     *
     * ```kotlin
     * val ex = AwsBluetapeException()
     * // ex.message == null
     * ```
     */
    constructor(): super()

    /**
     * Creates an exception instance with only a message.
     *
     * ## Behavior and contract
     * - Stores [message] as the superclass exception message.
     *
     * ```kotlin
     * val ex = AwsBluetapeException("error")
     * // ex.message == "error"
     * ```
     */
    constructor(message: String): super(message)

    /**
     * Creates an exception instance with a message and cause.
     *
     * ## Behavior and contract
     * - Passes [message] and [cause] to the superclass constructor.
     *
     * ```kotlin
     * val cause = IllegalStateException("boom")
     * val ex = AwsBluetapeException("wrapped", cause)
     * // ex.cause === cause
     * ```
     */
    constructor(message: String, cause: Throwable): super(message, cause)

    /**
     * Creates an exception instance with only a cause.
     *
     * ## Behavior and contract
     * - Passes [cause] to the superclass constructor.
     *
     * ```kotlin
     * val cause = IllegalArgumentException("invalid")
     * val ex = AwsBluetapeException(cause)
     * // ex.cause === cause
     * ```
     */
    constructor(cause: Throwable): super(cause)
}
