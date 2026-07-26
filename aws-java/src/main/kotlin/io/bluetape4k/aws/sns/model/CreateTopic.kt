package io.bluetape4k.aws.sns.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.services.sns.model.CreateTopicRequest

/**
 * Builds a [CreateTopicRequest] with a DSL block.
 *
 * ## Behavior/Contract
 * - Sets `name`, `attributes`, and other fields directly in the [builder] block.
 *
 * ```kotlin
 * val req = createTopicRequest { name("my-topic") }
 * ```
 */
inline fun createTopicRequest(
    builder: CreateTopicRequest.Builder.() -> Unit,
): CreateTopicRequest =
    CreateTopicRequest.builder().apply(builder).build()

/**
 * Creates a [CreateTopicRequest] from a topic name.
 *
 * ## Behavior/Contract
 * - Throws `IllegalArgumentException` when [name] is blank.
 *
 * ```kotlin
 * val req = createTopicRequestOf("my-topic")
 * // req.name() == "my-topic"
 * ```
 */
inline fun createTopicRequestOf(
    name: String,
    overrideConfiguration: AwsRequestOverrideConfiguration? = null,
    builder: CreateTopicRequest.Builder.() -> Unit = {},
): CreateTopicRequest {
    name.requireNotBlank("name")

    return createTopicRequest {
        name(name)
        overrideConfiguration?.let { overrideConfiguration(it) }

        builder()
    }
}
