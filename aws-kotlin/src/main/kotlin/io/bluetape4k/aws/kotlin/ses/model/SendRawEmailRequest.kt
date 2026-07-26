package io.bluetape4k.aws.kotlin.ses.model

import aws.sdk.kotlin.services.ses.model.RawMessage
import aws.sdk.kotlin.services.ses.model.SendRawEmailRequest

/**
 * Creates a [SendRawEmailRequest] from [RawMessage].
 *
 * ```kotlin
 * val request = sendRawEmailRequestOf(rawMessageOf(mimeBytes))
 * ```
 *
 * @param rawMessage [RawMessage] to send.
 * @return [SendRawEmailRequest] instance.
 */
inline fun sendRawEmailRequestOf(
    rawMessage: RawMessage,
    crossinline builder: SendRawEmailRequest.Builder.() -> Unit = {},
): SendRawEmailRequest =
    SendRawEmailRequest {
        this.rawMessage = rawMessage
        builder()
    }
