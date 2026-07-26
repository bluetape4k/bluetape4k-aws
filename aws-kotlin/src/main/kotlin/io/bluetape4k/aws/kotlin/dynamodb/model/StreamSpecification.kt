package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.StreamSpecification
import aws.sdk.kotlin.services.dynamodb.model.StreamViewType

/**
 * Builds a DynamoDB [StreamSpecification] with a DSL block.
 *
 * ## Behavior and contract
 * - Enables DynamoDB Streams when [streamEnabled] is true.
 * - [streamViewType] specifies the data written to the stream and is valid only when [streamEnabled] is true.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val spec = streamSpecificationOf(streamEnabled = true, streamViewType = StreamViewType.NewAndOldImages)
 * // spec.streamEnabled == true
 * // spec.streamViewType == StreamViewType.NewAndOldImages
 * ```
 *
 * @param streamEnabled whether streams are enabled.
 * @param streamViewType view type written to the stream.
 */
inline fun streamSpecificationOf(
    streamEnabled: Boolean? = null,
    streamViewType: StreamViewType? = null,
    crossinline builder: StreamSpecification.Builder.() -> Unit = {},
): StreamSpecification =
    StreamSpecification {
        this.streamEnabled = streamEnabled
        this.streamViewType = streamViewType

        builder()
    }
