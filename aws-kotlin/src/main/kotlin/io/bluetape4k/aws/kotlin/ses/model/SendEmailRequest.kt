package io.bluetape4k.aws.kotlin.ses.model

import aws.sdk.kotlin.services.ses.model.Destination
import aws.sdk.kotlin.services.ses.model.Message
import aws.sdk.kotlin.services.ses.model.SendEmailRequest
import io.bluetape4k.support.requireNotBlank

/**
 * Creates a [SendEmailRequest] from a source address, destination, and message.
 *
 * ```kotlin
 * val request = sendEmailRequestOf(
 *     source = "sender@example.com",
 *     destination = destinationOf("user@example.com"),
 *     message = messageOf(contentOf("Hello"), textBodyOf(contentOf("Hello, World!"))),
 * )
 * ```
 *
 * @param source sender email address. It must not be blank.
 * @param destination recipient [Destination].
 * @param message [Message] to send.
 * @return [SendEmailRequest] instance.
 */
inline fun sendEmailRequestOf(
    source: String,
    destination: Destination,
    message: Message,
    crossinline builder: SendEmailRequest.Builder.() -> Unit = {},
): SendEmailRequest {
    source.requireNotBlank("source")

    return SendEmailRequest {
        this.source = source
        this.destination = destination
        this.message = message

        builder()
    }
}

@Deprecated(
    message = "Typo in function name. Use sendEmailRequestOf instead.",
    replaceWith =
        ReplaceWith(
            expression = "sendEmailRequestOf(source, destination, message, builder)",
            imports = ["io.bluetape4k.aws.kotlin.ses.model.sendEmailRequestOf"]
        )
)
inline fun sendMailRequestOf(
    source: String,
    destination: Destination,
    message: Message,
    crossinline builder: SendEmailRequest.Builder.() -> Unit = {},
): SendEmailRequest = sendEmailRequestOf(source, destination, message, builder)
