package io.bluetape4k.aws.kotlin.ses.model

import aws.sdk.kotlin.services.ses.model.Body
import aws.sdk.kotlin.services.ses.model.Content
import aws.sdk.kotlin.services.ses.model.Message
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
inline fun contentOf(
    data: String,
    charset: String = Charsets.UTF_8.name(),
    crossinline builder: Content.Builder.() -> Unit = {},
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
inline fun htmlBodyOf(
    html: Content? = null,
    crossinline builder: Body.Builder.() -> Unit = {},
): Body =
    Body {
        this.html = html
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
inline fun textBodyOf(
    text: Content? = null,
    crossinline builder: Body.Builder.() -> Unit = {},
): Body =
    Body {
        this.text = text
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
inline fun messageOf(
    subject: Content,
    body: Body,
    crossinline builder: Message.Builder.() -> Unit = {},
): Message =
    Message {
        this.subject = subject
        this.body = body

        builder()
    }
