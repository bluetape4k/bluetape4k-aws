package io.bluetape4k.aws.kotlin.sesv2.model

import aws.sdk.kotlin.services.sesv2.model.Body
import aws.sdk.kotlin.services.sesv2.model.Content
import aws.sdk.kotlin.services.sesv2.model.Message
import io.bluetape4k.support.requireNotBlank

/**
 * Creates email [Content].
 *
 * ```kotlin
 * val subject = contentOf("Hello, World!")
 * ```
 *
 * @param data content string. It must not be blank.
 * @param charset character encoding. Defaults to UTF-8.
 * @return [Content] instance.
 */
fun contentOf(
    data: String,
    charset: String = Charsets.UTF_8.name(),
    builder: Content.Builder.() -> Unit = {},
): Content {
    data.requireNotBlank("data")

    return Content {
        this.data = data
        this.charset = charset

        builder()
    }
}

/**
 * Creates an HTML [Body].
 *
 * ```kotlin
 * val body = htmlBodyOf(contentOf("<h1>Hello</h1>"))
 * ```
 *
 * @param html HTML [Content], or `null` to omit it.
 * @return [Body] instance.
 */
fun htmlBodyOf(
    html: Content? = null,
    builder: Body.Builder.() -> Unit = {},
): Body =
    Body {
        html?.let { this.html = it }
        builder()
    }

/**
 * Creates a text [Body].
 *
 * ```kotlin
 * val body = textBodyOf(contentOf("Hello, World!"))
 * ```
 *
 * @param text text [Content], or `null` to omit it.
 * @return [Body] instance.
 */
fun textBodyOf(
    text: Content? = null,
    builder: Body.Builder.() -> Unit = {},
): Body =
    Body {
        text?.let { this.text = it }
        builder()
    }

/**
 * Creates an email [Message] from a subject and body.
 *
 * ```kotlin
 * val message = messageOf(
 *     subject = contentOf("Hello"),
 *     body = textBodyOf(contentOf("Hello, World!")),
 * )
 * ```
 *
 * @param subject email subject [Content].
 * @param body email [Body].
 * @return [Message] instance.
 */
fun messageOf(
    subject: Content,
    body: Body,
    builder: Message.Builder.() -> Unit = {},
): Message =
    Message {
        this.subject = subject
        this.body = body

        builder()
    }
