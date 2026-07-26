package io.bluetape4k.aws.ses.model

import software.amazon.awssdk.services.ses.model.Destination

/**
 * Creates a [destination] instance with [Destination.Builder].
 *
 * ```kotlin
 * val destination = destination {
 *    toAddresses("user1@example.com")
 *    ccAddresses("user2@example.com")
 *    bccAddresses("all@example.com")
 * }
 * ```
 *
 * @param builder [Destination.Builder] initialization lambda.
 * @return [destination] instance.
 */
inline fun destination(
    builder: Destination.Builder.() -> Unit,
): Destination =
    Destination.builder().apply(builder).build()

/**
 * Creates a [Destination] instance.
 *
 * ```kotlin
 * val destination = destinationOf(listOf("debop@example.com", "user1@example.com"))
 * ```
 *
 * @param toAddrs recipient address list.
 * @param ccAddrs CC address list.
 * @param bccAddrs BCC address list.
 * @return [Destination] instance.
 */
fun destinationOf(
    toAddrs: Collection<String>,
    ccAddrs: Collection<String>? = null,
    bccAddrs: Collection<String>? = null,
) = destination {
    toAddresses(toAddrs)
    ccAddrs?.let { ccAddresses(it) }
    bccAddrs?.let { bccAddresses(it) }
}

/**
 * Creates a [Destination] instance.
 *
 * ```kotlin
 * val destination = destinationOf("debop@example.com", "user1@example.com")
 * ```
 *
 * @param toAddrs recipient address list.
 * @return [Destination] instance.
 */
fun destinationOf(vararg toAddrs: String): Destination = destination {
    toAddresses(*toAddrs)
}
