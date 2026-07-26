package io.bluetape4k.aws.kotlin.sesv2.model

import aws.sdk.kotlin.services.sesv2.model.Destination
import io.bluetape4k.support.requireNotEmpty

/**
 * Creates a [Destination] from recipient addresses.
 *
 * ```kotlin
 * val dest = destinationOf("user1@example.com", "user2@example.com")
 * ```
 *
 * @param toAddress recipient (TO) email addresses. At least one address is required.
 * @return [Destination] instance.
 */
fun destinationOf(
    vararg toAddress: String,
    configurer: Destination.Builder.() -> Unit = {},
): Destination {
    toAddress.requireNotEmpty("toAddress")

    return Destination {
        this.toAddresses = toAddress.toList()

        configurer()
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
fun destinationOf(
    toAddresses: List<String>? = null,
    ccAddresses: List<String>? = null,
    bccAddresses: List<String>? = null,
    configurer: Destination.Builder.() -> Unit = {},
): Destination {
    val hasAddress = !toAddresses.isNullOrEmpty() || !ccAddresses.isNullOrEmpty() || !bccAddresses.isNullOrEmpty()
    require(hasAddress) { "At least one address must be provided." }

    return Destination {
        toAddresses?.let { this.toAddresses = it }
        ccAddresses?.let { this.ccAddresses = it }
        bccAddresses?.let { this.bccAddresses = it }
        configurer()
    }
}
