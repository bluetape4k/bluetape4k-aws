package io.bluetape4k.aws.ses.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.ses.model.Body
import software.amazon.awssdk.services.ses.model.Content
import software.amazon.awssdk.services.ses.model.Message
import software.amazon.awssdk.services.ses.model.MessageTag
import java.nio.charset.Charset

/**
 * Creates a [message] instance with [Message.Builder].
 *
 * ```kotlin
 * val message = Message {
 *    subject {
 *        data("Hello")
 *        charset("UTF-8")
 *        ...
 *    }
 *    body {
 *        text {
 *            data("Hello")
 *        }
 *        html {
 *            data("<p>Hello</p>")
 *        }
 *    }
 * }
 * ```
 *
 * @param builder [Message.Builder] initialization lambda.
 * @return [message] instance.
 */
inline fun message(
    builder: Message.Builder.() -> Unit,
): Message {
    return Message.builder().apply(builder).build()
}

/**
 * Creates a [Message] instance.
 *
 * ```kotlin
 * val message = messageOf(
 *     subject = contentOf("Hello", Charsets.UTF_8),
 *     body = bodyOf("Hello", "<p>Hello</p>", Charsets.UTF_8)
 * )
 * ```
 *
 * @param subject [Content] subject.
 * @param body [Body] body.
 * @return [Message] instance.
 */
fun messageOf(
    subject: Content,
    body: Body,
): Message = message {
    subject(subject)
    body(body)
}

/**
 * Creates a [body] instance with [Body.Builder].
 *
 * ```kotlin
 * val body = Body {
 *    text {
 *        contentOf("Hello", Charsets.UTF_8)
 *    }
 *    html {
 *        contentOf("<p>Hello</p>", Charsets.UTF_8)
 *    }
 * }
 * ```
 *
 * @param builder [Body.Builder] initialization lambda.
 * @return [body] instance.
 */
inline fun body(
    builder: Body.Builder.() -> Unit,
): Body =
    Body.builder().apply(builder).build()

/**
 * Creates a [Body] instance.
 *
 * ```kotlin
 * val body = bodyOf("Hello", "<p>Hello</p>", Charsets.UTF_8)
 * ```
 *
 * @param text [String] text body.
 * @param html [String] HTML body.
 * @param charset [Charset] charset.
 * @return [Body] instance.
 */
fun bodyOf(
    text: String,
    html: String,
    charset: Charset = Charsets.UTF_8,
): Body = body {
    text(contentOf(text, charset))
    html(contentOf(html, charset))
}

/**
 * Creates a text [Body] instance.
 *
 * ```kotlin
 * val body = bodyOf("Hello", Charsets.UTF_8)
 * ```
 *
 * @param text [String] text body.
 * @param charset [Charset] charset.
 * @return [Body] instance.
 */
fun bodyAsText(
    text: String,
    charset: Charset = Charsets.UTF_8,
): Body = body {
    text(contentOf(text, charset))
}

/**
 * Creates an HTML [Body] instance.
 *
 * ```kotlin
 * val body = bodyOf("<p>Hello</p>", Charsets.UTF_8)
 * ```
 *
 * @param html [String] HTML body.
 * @param charset [Charset] charset.
 * @return [Body] instance.
 */
fun bodyAsHtml(
    html: String,
    charset: Charset = Charsets.UTF_8,
): Body = body {
    html(contentOf(html, charset))
}

/**
 * Creates a [content] instance with [Content.Builder].
 *
 * ```kotlin
 * val content = Content {
 *    data("Hello")
 *    charset("UTF-8")
 * }
 * ```
 *
 * @param builder [Content.Builder] initialization lambda.
 * @return [content] instance.
 */
inline fun content(
    builder: Content.Builder.() -> Unit,
): Content {
    return Content.builder().apply(builder).build()
}

/**
 * Creates a [Content] instance.
 *
 * ```kotlin
 * val content = contentOf("Hello", Charsets.UTF_8)
 * ```
 *
 * @param data [String] data.
 * @param charset [Charset] charset.
 * @return [Content] instance.
 */
fun contentOf(data: String? = null, charset: Charset = Charsets.UTF_8) = content {
    data?.let { data(it) }
    charset(charset.name())
}

/**
 * Creates a [messageTag] instance with [MessageTag.Builder].
 *
 * ```kotlin
 * val messageTag = messageTag {
 *    name("key")
 *    value("value")
 * }
 * ```
 *
 * @param builder [MessageTag.Builder] initialization lambda.
 * @return [messageTag] instance.
 */
inline fun messageTag(
    builder: MessageTag.Builder.() -> Unit,
): MessageTag {
    return MessageTag.builder().apply(builder).build()
}

/**
 * Creates a [MessageTag] instance.
 *
 * ```kotlin
 * val messageTag = messageTagOf("key", "value")
 * ```
 *
 * @param name [String] tag name.
 * @param value [String] tag value.
 * @return [MessageTag] instance.
 */
fun messageTagOf(
    name: String,
    value: String,
): MessageTag {
    name.requireNotBlank("name")
    value.requireNotBlank("value")

    return messageTag {
        name(name)
        value(value)
    }
}
