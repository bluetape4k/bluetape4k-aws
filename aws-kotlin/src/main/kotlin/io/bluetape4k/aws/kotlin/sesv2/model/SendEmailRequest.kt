package io.bluetape4k.aws.kotlin.sesv2.model

import aws.sdk.kotlin.services.sesv2.model.Destination
import aws.sdk.kotlin.services.sesv2.model.EmailContent
import aws.sdk.kotlin.services.sesv2.model.SendEmailRequest
import io.bluetape4k.support.requireNotBlank

/**
 * Creates a [SendEmailRequest] from a source address, destination, and email content.
 *
 * ```kotlin
 * val request = sendEmailRequestOf(
 *     fromEmailAddress = "sender@example.com",
 *     destination = destinationOf("user@example.com"),
 *     content = emailContent,
 * )
 * ```
 *
 * @param fromEmailAddress sender email address. It must not be blank.
 * @param destination recipient [Destination].
 * @param content [EmailContent] to send.
 * @return [SendEmailRequest] instance.
 */
fun sendEmailRequestOf(
    fromEmailAddress: String,
    destination: Destination,
    content: EmailContent,
    builder: SendEmailRequest.Builder.() -> Unit = {},
): SendEmailRequest {
    fromEmailAddress.requireNotBlank("fromEmailAddress")

    return SendEmailRequest {
        this.fromEmailAddress = fromEmailAddress
        this.destination = destination
        this.content = content

        builder()
    }
}

@Deprecated(
    message = "Typo in function name. Use sendEmailRequestOf instead.",
    replaceWith =
        ReplaceWith(
            expression = "sendEmailRequestOf(fromEmailAddress, destination, content, builder)",
            imports = ["io.bluetape4k.aws.kotlin.sesv2.model.sendEmailRequestOf"]
        )
)
fun sendMailRequestOf(
    fromEmailAddress: String,
    destination: Destination,
    content: EmailContent,
    builder: SendEmailRequest.Builder.() -> Unit = {},
): SendEmailRequest = sendEmailRequestOf(fromEmailAddress, destination, content, builder)
