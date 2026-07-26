package io.bluetape4k.aws.kotlin.ses.model

import aws.sdk.kotlin.services.ses.model.Destination
import io.bluetape4k.support.requireNotEmpty

/**
 * Creates a [Destination] from recipient addresses.
 *
 * ```kotlin
 * val dest = destinationOf("user1@example.com", "user2@example.com")
 * ```
 *
 * @param toAddresses recipient (TO) email addresses. At least one address is required.
 * @return [Destination] instance.
 */
inline fun destinationOf(
    vararg toAddresses: String,
    crossinline builder: Destination.Builder.() -> Unit = {},
): Destination {
    toAddresses.requireNotEmpty("toAddresses")

    return Destination {
        this.toAddresses = toAddresses.toList()

        builder()
    }
}

/**
 * Creates a [Destination] from TO, CC, and BCC recipient address lists.
 *
 * ```kotlin
 * val dest = destinationOf(
 *     toAddresses = listOf("user1@example.com"),
 *     ccAddresses = listOf("cc@example.com"),
 * )
 * ```
 *
 * @param toAddresses recipient (TO) email addresses.
 * @param ccAddresses carbon copy (CC) email addresses.
 * @param bccAddresses blind carbon copy (BCC) email addresses.
 * @return [Destination] instance.
 */
inline fun destinationOf(
    toAddresses: List<String>? = null,
    ccAddresses: List<String>? = null,
    bccAddresses: List<String>? = null,
    crossinline builder: Destination.Builder.() -> Unit = {},
): Destination {
    val hasAddress = !toAddresses.isNullOrEmpty() || !ccAddresses.isNullOrEmpty() || !bccAddresses.isNullOrEmpty()
    require(hasAddress) { "At least one address must be provided." }

    return Destination {
        toAddresses?.let { this.toAddresses = it }
        ccAddresses?.let { this.ccAddresses = it }
        bccAddresses?.let { this.bccAddresses = it }

        builder()
    }
}
