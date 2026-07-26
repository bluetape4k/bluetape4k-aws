package io.bluetape4k.aws.kotlin.sesv2.model

import aws.sdk.kotlin.services.sesv2.model.RawMessage

/**
 * Creates an SES V2 [RawMessage] from a byte array.
 *
 * ```kotlin
 * val raw = rawMessageOf(mimeBytes)
 * ```
 *
 * @param data MIME-formatted raw email bytes. It must not be empty.
 * @return [RawMessage] instance.
 */
fun rawMessageOf(data: ByteArray): RawMessage {
    require(data.isNotEmpty()) { "data must not be empty." }

    return RawMessage {
        this.data = data
    }
}
